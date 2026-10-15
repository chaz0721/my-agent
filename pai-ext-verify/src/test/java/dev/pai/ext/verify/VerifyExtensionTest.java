/* pai-next — Apache-2.0 */
package dev.pai.ext.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.UserInteraction;
import dev.pai.core.loop.LoopState;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;
import dev.pai.model.Usage;

import static org.assertj.core.api.Assertions.assertThat;

class VerifyExtensionTest {

    @TempDir
    Path cwd;
    @TempDir
    Path home;

    private final List<String> commands = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    private int exitCode;

    /** Records commands and answers with {@link #exitCode}. */
    private final Tool bash = new Tool() {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("bash", "fake", JsonSchema.object().build());
        }

        @Override
        public ToolEffectEnum effect() {
            return ToolEffectEnum.EXECUTE;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext ctx) {
            commands.add(arguments.path("command").asText() + " @" + arguments.path("timeoutSeconds").asInt());
            String out = "x".repeat(3000) + "\nERROR Foo.java:[3,1] ';' expected\n[exit code: " + exitCode + "]";
            return new ToolResult(List.of(new ContentBlock.Text(out)), false,
                Optional.of(Json.mapper().createObjectNode().put("exitCode", exitCode).put("killed", false)));
        }
    };

    private final List<String> questions = new ArrayList<>();
    private boolean agree = true;

    private final UserInteraction ui = new UserInteraction() {
        @Override
        public String ask(String prompt, List<String> choices) {
            return "";
        }

        @Override
        public boolean confirm(String prompt) {
            questions.add(prompt);
            return agree;
        }
    };

    private ExtensionHost host(boolean trusted) {
        return host(trusted, Optional.of(ui));
    }

    private ExtensionHost host(boolean trusted, Optional<UserInteraction> interaction) {
        ExtensionHost h = new ExtensionLoader().install(List.of(new VerifyExtension()),
            new ExtensionHost.Settings(home, cwd, trusted, interaction, notices::add));
        h.tools().register(bash, "test");
        return h;
    }

    private static ContentBlock.ToolCall call(String id, String tool, String path) {
        return new ContentBlock.ToolCall(id, tool, Json.mapper().createObjectNode().put("path", path));
    }

    private static LoopState turn(ContentBlock.ToolCall... calls) {
        AssistantMessage a = new AssistantMessage(List.of(calls), "fake", "m", Usage.ZERO, StopReasonEnum.TOOL_USE,
            Optional.empty(), Instant.now());
        return new LoopState() {
            @Override
            public List<Message> messages() {
                return List.of(a);
            }

            @Override
            public ModelRef model() {
                return null;
            }

            @Override
            public Usage usageThisRun() {
                return Usage.ZERO;
            }

            @Override
            public int turnIndex() {
                return 0;
            }

            @Override
            public Path cwd() {
                return Path.of(".");
            }
        };
    }

    private static String text(ToolResult r) {
        return r.content().stream().map(b -> ((ContentBlock.Text)b).text()).reduce("", (x, y) -> x + "|" + y);
    }

    @Test
    void a_maven_project_gets_the_compile_rule_and_it_is_announced() throws IOException {
        Files.writeString(cwd.resolve("pom.xml"), "<project/>");
        host(true);
        assertThat(notices).containsExactly("verify: on write|edit **/{*.java,pom.xml} -> mvn -q compile");
    }

    @Test
    void each_build_file_selects_its_rule_and_none_selects_nothing() throws IOException {
        assertThat(VerifyExtension.builtin(cwd)).isEmpty();
        for (String marker : List.of("package.json", "Cargo.toml", "go.mod")) {
            Files.writeString(cwd.resolve(marker), "{\"scripts\":{\"build\":\"tsc\"}}");
        }
        assertThat(VerifyExtension.builtin(cwd)).extracting(VerifyRule::run).containsExactly("cargo check",
            "go build ./...");
        Files.writeString(cwd.resolve("package.json"), "{\"scripts\":{\"typecheck\":\"tsc --noEmit\"}}");
        assertThat(VerifyExtension.builtin(cwd)).extracting(VerifyRule::run).first().isEqualTo("npm run -s typecheck");
    }

    @Test
    void a_failed_check_is_appended_with_its_label_and_a_bounded_tail() throws IOException {
        Files.writeString(cwd.resolve("pom.xml"), "<project/>");
        ExtensionHost h = host(true);
        exitCode = 1;
        ContentBlock.ToolCall edit = call("c1", "edit", "src/main/java/Foo.java");
        ToolResult r = h.hooks().afterToolCall(edit, ToolResult.text("edited"), turn(edit));
        assertThat(commands).containsExactly("mvn -q compile @180");
        assertThat(r.isError()).isFalse();
        assertThat(r.content()).hasSize(2);
        String report = ((ContentBlock.Text)r.content().get(1)).text();
        assertThat(report).startsWith("[verify: mvn -q compile] failed (exit 1), output:\nxxx")
            .contains("characters left out", "';' expected");
        assertThat(report.length()).isLessThan(2 * VerifyRunner.KEEP_EACH_END + 150);
        assertThat(questions).singleElement().asString().isEqualTo(VerifyConsent.question("mvn -q compile"))
            .contains("the model can change them by writing files");
        exitCode = 0;
        assertThat(text(h.hooks().afterToolCall(edit, ToolResult.text("edited"), turn(edit))))
            .endsWith("|[verify: mvn -q compile] passed");
    }

    @Test
    void several_writes_of_one_reply_run_the_check_once_after_the_last() throws IOException {
        Files.writeString(cwd.resolve("pom.xml"), "<project/>");
        ExtensionHost h = host(true);
        ContentBlock.ToolCall a = call("a", "write", "A.java");
        ContentBlock.ToolCall readme = call("r", "write", "README.md");
        ContentBlock.ToolCall b = call("b", "edit", "src/B.java");
        LoopState state = turn(a, readme, b);
        assertThat(text(h.hooks().afterToolCall(a, ToolResult.text("wrote a"), state))).isEqualTo("|wrote a");
        assertThat(text(h.hooks().afterToolCall(readme, ToolResult.text("wrote r"), state))).isEqualTo("|wrote r");
        assertThat(commands).isEmpty();
        assertThat(text(h.hooks().afterToolCall(b, ToolResult.error("blocked"), state))).contains("passed");
        assertThat(commands).hasSize(1);
    }

    @Test
    void nothing_runs_when_no_matching_write_succeeded_or_paths_do_not_match() throws IOException {
        Files.writeString(cwd.resolve("pom.xml"), "<project/>");
        ExtensionHost h = host(true);
        ContentBlock.ToolCall a = call("a", "edit", "A.java");
        h.hooks().afterToolCall(a, ToolResult.error("blocked"), turn(a));
        ContentBlock.ToolCall outside = call("o", "edit", "../elsewhere/X.java");
        h.hooks().afterToolCall(outside, ToolResult.text("ok"), turn(outside));
        ContentBlock.ToolCall read = call("r", "read", "A.java");
        h.hooks().afterToolCall(read, ToolResult.text("ok"), turn(read));
        assertThat(commands).isEmpty();
    }

    @Test
    void verify_off_stops_automatic_runs_and_verify_runs_now() throws IOException {
        Files.writeString(cwd.resolve("pom.xml"), "<project/>");
        ExtensionHost h = host(true);
        assertThat(h.command("verify").orElseThrow().run("", null).output())
            .isEqualTo("[verify: mvn -q compile] passed");
        assertThat(h.command("verify").orElseThrow().run("off", null).output()).contains("off");
        ContentBlock.ToolCall a = call("a", "edit", "A.java");
        h.hooks().afterToolCall(a, ToolResult.text("ok"), turn(a));
        assertThat(commands).hasSize(1);
    }

    @Test
    void project_rules_need_a_trusted_project() throws IOException {
        Files.writeString(cwd.resolve("pom.xml"), "<project/>");
        Files.createDirectories(cwd.resolve(".pai"));
        Files.writeString(cwd.resolve(VerifyExtension.PROJECT_FILE),
            "[{\"on\":\"write\",\"pathGlob\":\"*.txt\",\"run\":\"echo hi\",\"timeoutSeconds\":5,\"mode\":\"replace\"}]");
        host(false);
        assertThat(notices).first().asString().contains("ignoring ./.pai/verify.json (project not trusted)");
        assertThat(notices).last().asString().contains("mvn -q compile");
        notices.clear();
        ExtensionHost h = host(true);
        assertThat(notices).containsExactly("verify: on write *.txt -> echo hi");
        ContentBlock.ToolCall w = call("w", "write", "a.txt");
        assertThat(text(h.hooks().afterToolCall(w, ToolResult.text("wrote"), turn(w))))
            .isEqualTo("|[verify: echo hi] passed");
    }

    @Test
    void globs_match_top_level_and_nested_paths() throws JsonProcessingException {
        VerifyRule r = VerifyRule.parse(Json.mapper().readTree("{\"pathGlob\":\"**/{*.java,pom.xml}\",\"run\":\"x\"}"));
        Path root = cwd.toAbsolutePath().normalize();
        assertThat(r.matches("edit", root, "Foo.java")).isTrue();
        assertThat(r.matches("write", root, "a/b/Foo.java")).isTrue();
        assertThat(r.matches("write", root, "a/pom.xml")).isTrue();
        assertThat(r.matches("write", root, "Foo.javax")).isFalse();
        assertThat(r.matches("bash", root, "Foo.java")).isFalse();
        assertThat(VerifyRule.glob("a,b").matcher("a,b").matches()).isTrue();
    }

    @Test
    void a_turn_that_writes_a_build_definition_is_not_verified() throws IOException {
        Files.writeString(cwd.resolve("pom.xml"), "<project/>");
        ExtensionHost h = host(true);
        ContentBlock.ToolCall pom = call("p", "edit", "pom.xml");
        ContentBlock.ToolCall java = call("j", "edit", "src/A.java");
        LoopState state = turn(pom, java);
        h.hooks().afterToolCall(pom, ToolResult.text("ok"), state);
        assertThat(text(h.hooks().afterToolCall(java, ToolResult.text("ok"), state))).contains("skipped");
        ContentBlock.ToolCall wrapper = call("w", "write", ".mvn/wrapper/maven-wrapper.properties");
        ContentBlock.ToolCall other = call("o", "write", "B.java");
        LoopState second = turn(wrapper, other);
        h.hooks().afterToolCall(wrapper, ToolResult.text("ok"), second);
        h.hooks().afterToolCall(other, ToolResult.text("ok"), second);
        assertThat(commands).isEmpty();
        assertThat(notices).contains(VerifyHooks.SKIPPED);
        assertThat(questions).isEmpty();
    }

    @Test
    void a_refusal_switches_automatic_runs_off_and_is_asked_once() throws IOException {
        Files.writeString(cwd.resolve("pom.xml"), "<project/>");
        agree = false;
        ExtensionHost h = host(true);
        for (String id : List.of("a", "b")) {
            ContentBlock.ToolCall c = call(id, "edit", "A.java");
            h.hooks().afterToolCall(c, ToolResult.text("ok"), turn(c));
        }
        assertThat(commands).isEmpty();
        assertThat(questions).hasSize(1);
        assertThat(notices).anyMatch(n -> n.contains("automatic runs off for this session"));
        assertThat(h.command("verify").orElseThrow().run("", null).output()).contains("passed");
    }

    @Test
    void without_a_terminal_nothing_runs_automatically() throws IOException {
        Files.writeString(cwd.resolve("pom.xml"), "<project/>");
        ExtensionHost h = host(true, Optional.empty());
        ContentBlock.ToolCall c = call("a", "edit", "A.java");
        assertThat(text(h.hooks().afterToolCall(c, ToolResult.text("ok"), turn(c)))).isEqualTo("|ok");
        assertThat(commands).isEmpty();
        assertThat(notices).anyMatch(n -> n.contains("nobody can confirm"));
    }

    @Test
    void maven_noise_is_dropped_and_short_output_is_kept_whole() {
        String out = "[ERROR] A.java:[1,1] boom\n[ERROR] -> [Help 1]\n[ERROR] Re-run Maven using the -X switch\nend";
        assertThat(VerifyRunner.excerpt(out, false)).isEqualTo("[ERROR] A.java:[1,1] boom\nend");
        assertThat(VerifyRunner.excerpt("short", true)).isEqualTo("short\n[output was truncated]");
    }

    @Test
    void a_cancelled_manual_run_stops() throws IOException {
        Files.writeString(cwd.resolve("pom.xml"), "<project/>");
        host(true);
        VerifyHooks hooks = new VerifyHooks(
            new VerifyHooks.Parts(VerifyExtension.builtin(cwd), new VerifyRunner(n -> Optional.of(bash), cwd), cwd,
                new VerifyConsent(Optional.empty(), notices::add), notices::add));
        CancelTokenSource cancelled = new CancelTokenSource();
        cancelled.cancel();
        assertThat(hooks.runAll(cancelled)).isEmpty();
        assertThat(hooks.runAll(CancelToken.none())).hasSize(1);
    }

    @Test
    void a_changed_build_definition_keeps_later_turns_unverified_until_verify_passes() throws IOException {
        Files.writeString(cwd.resolve("package.json"), "{\"scripts\":{\"typecheck\":\"tsc --noEmit\"}}");
        ExtensionHost h = host(true);
        ContentBlock.ToolCall scripts = call("p", "write", "package.json");
        h.hooks().afterToolCall(scripts, ToolResult.text("wrote"), turn(scripts));
        ContentBlock.ToolCall ts = call("t", "write", "src/a.ts");
        assertThat(text(h.hooks().afterToolCall(ts, ToolResult.text("wrote"), turn(ts)))).contains("skipped");
        ContentBlock.ToolCall again = call("t2", "edit", "src/a.ts");
        assertThat(text(h.hooks().afterToolCall(again, ToolResult.text("edited"), turn(again)))).contains("skipped");
        assertThat(commands).isEmpty();
        assertThat(h.command("verify").orElseThrow().run("", null).output()).contains("passed");
        ContentBlock.ToolCall later = call("t3", "edit", "src/a.ts");
        assertThat(text(h.hooks().afterToolCall(later, ToolResult.text("edited"), turn(later)))).contains("passed");
        assertThat(commands).hasSize(2);
    }

    @Test
    void a_failed_write_of_a_build_definition_changes_nothing_and_verify_on_asks_again() throws IOException {
        Files.writeString(cwd.resolve("pom.xml"), "<project/>");
        ExtensionHost h = host(true);
        ContentBlock.ToolCall denied = call("p", "edit", "pom.xml");
        h.hooks().afterToolCall(denied, ToolResult.error("blocked"), turn(denied));
        ContentBlock.ToolCall java = call("j", "edit", "A.java");
        assertThat(text(h.hooks().afterToolCall(java, ToolResult.text("ok"), turn(java)))).contains("passed");
        ContentBlock.ToolCall pom = call("p2", "edit", "pom.xml");
        h.hooks().afterToolCall(pom, ToolResult.text("ok"), turn(pom));
        agree = false;
        assertThat(h.command("verify").orElseThrow().run("on", null).output()).contains("stay off");
        agree = true;
        assertThat(h.command("verify").orElseThrow().run("on", null).output()).isEqualTo("verify: on");
        ContentBlock.ToolCall next = call("j2", "edit", "A.java");
        assertThat(text(h.hooks().afterToolCall(next, ToolResult.text("ok"), turn(next)))).contains("passed");
    }
}
