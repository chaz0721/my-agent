/* pai-next — Apache-2.0 */
package dev.pai.ext.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;
import dev.pai.model.Usage;

import static org.assertj.core.api.Assertions.assertThat;

/** M8.5B: the built-in Python rule, the {path} and {python} placeholders. */
class VerifyPythonTest {

    static final String SYNTAX_ERROR = "*** Error compiling 'pkg/a.py'...\n  File \"pkg/a.py\", line 2\n    def f(:\n"
        + "          ^\nSyntaxError: invalid syntax";

    @TempDir
    Path cwd;
    @TempDir
    Path home;

    private final List<String> commands = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    /** Interpreters whose {@code --version} succeeds. */
    private Set<String> installed = Set.of("python");
    private boolean broken = true;

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
            String command = arguments.path("command").asText();
            commands.add(command);
            String first = command.split(" ")[0];
            int exit = command.endsWith("--version") ? installed.contains(first) ? 0 : 127 : broken ? 1 : 0;
            String out = exit == 0 ? "" : command.endsWith("--version") ? first + ": command not found" : SYNTAX_ERROR;
            return new ToolResult(List.of(new ContentBlock.Text(out)), false,
                Optional.of(Json.mapper().createObjectNode().put("exitCode", exit)));
        }
    };

    private final UserInteraction ui = new UserInteraction() {
        @Override
        public String ask(String prompt, List<String> choices) {
            return "";
        }

        @Override
        public boolean confirm(String prompt) {
            return true;
        }
    };

    private ExtensionHost host() {
        ExtensionHost h = new ExtensionLoader().install(List.of(new VerifyExtension()),
            new ExtensionHost.Settings(home, cwd, true, Optional.of(ui), notices::add));
        h.tools().register(bash, "test");
        return h;
    }

    private ContentBlock.ToolCall written(String id, String tool, String path) throws IOException {
        Path file = cwd.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "def f(:\n");
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

    private static String last(ToolResult r) {
        return ((ContentBlock.Text)r.content().get(r.content().size() - 1)).text();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pyproject.toml", "setup.py", "setup.cfg", "requirements.txt"})
    void any_python_marker_selects_the_rule(String marker) throws IOException {
        assertThat(VerifyExtension.builtin(cwd)).isEmpty();
        Files.writeString(cwd.resolve(marker), "");
        assertThat(VerifyExtension.builtin(cwd)).singleElement().extracting(VerifyRule::run)
            .isEqualTo("{python} -m compileall -q {path}");
        assertThat(VerifyExtension.builtin(cwd).get(0).timeoutSeconds()).isEqualTo(30);
    }

    @Test
    void only_the_written_files_are_compiled_and_a_syntax_error_is_fed_back() throws IOException {
        Files.writeString(cwd.resolve("pyproject.toml"), "");
        ExtensionHost h = host();
        ContentBlock.ToolCall a = written("a", "edit", "pkg/a.py");
        ContentBlock.ToolCall readme = written("r", "write", "README.md");
        ContentBlock.ToolCall b = written("b", "write", "./pkg/../b.py");
        LoopState state = turn(a, readme, b);
        h.hooks().afterToolCall(a, ToolResult.text("edited"), state);
        h.hooks().afterToolCall(readme, ToolResult.text("wrote"), state);
        ToolResult r = h.hooks().afterToolCall(b, ToolResult.text("wrote"), state);
        assertThat(commands).containsExactly("python --version", "python -m compileall -q pkg/a.py b.py");
        assertThat(last(r)).startsWith("[verify: python -m compileall -q pkg/a.py b.py] failed (exit 1), output:\n")
            .contains("SyntaxError: invalid syntax", "pkg/a.py\", line 2");
        broken = false;
        ContentBlock.ToolCall fix = written("c", "edit", "pkg/a.py");
        assertThat(last(h.hooks().afterToolCall(fix, ToolResult.text("edited"), turn(fix))))
            .isEqualTo("[verify: python -m compileall -q pkg/a.py] passed");
        assertThat(commands).filteredOn(c -> c.endsWith("--version")).hasSize(1);
    }

    @Test
    void python3_is_the_fallback_and_without_either_the_rule_is_skipped_with_one_notice() throws IOException {
        Files.writeString(cwd.resolve("requirements.txt"), "");
        installed = Set.of("python3");
        ExtensionHost h = host();
        ContentBlock.ToolCall a = written("a", "edit", "a.py");
        assertThat(last(h.hooks().afterToolCall(a, ToolResult.text("ok"), turn(a))))
            .startsWith("[verify: python3 -m compileall -q a.py] failed");
        installed = Set.of();
        commands.clear();
        ExtensionHost none = host();
        for (String id : List.of("x", "y")) {
            ContentBlock.ToolCall c = written(id, "edit", "a.py");
            assertThat(none.hooks().afterToolCall(c, ToolResult.text("ok"), turn(c)).content()).hasSize(1);
        }
        assertThat(commands).containsExactly("python --version", "python3 --version");
        assertThat(notices).filteredOn(n -> n.contains("none of python, python3 runs")).hasSize(1);
    }

    @Test
    void a_path_that_is_not_plain_is_not_put_in_the_command() throws IOException {
        Files.writeString(cwd.resolve("setup.py"), "");
        ExtensionHost h = host();
        ContentBlock.ToolCall odd = written("o", "write", "x;rm -rf ~.py");
        assertThat(last(h.hooks().afterToolCall(odd, ToolResult.text("ok"), turn(odd))))
            .contains("skipped: the path x;rm -rf ~.py is not plain enough");
        assertThat(commands).containsExactly("python --version");
    }

    @Test
    void writing_a_python_build_definition_skips_the_turn_and_verify_compiles_the_tree() throws IOException {
        Files.writeString(cwd.resolve("setup.cfg"), "");
        ExtensionHost h = host();
        ContentBlock.ToolCall cfg = written("p", "edit", "pyproject.toml");
        ContentBlock.ToolCall a = written("a", "edit", "a.py");
        LoopState state = turn(cfg, a);
        h.hooks().afterToolCall(cfg, ToolResult.text("ok"), state);
        assertThat(last(h.hooks().afterToolCall(a, ToolResult.text("ok"), state))).contains("skipped");
        assertThat(commands).isEmpty();
        broken = false;
        assertThat(h.command("verify").orElseThrow().run("", null).output())
            .isEqualTo("[verify: python -m compileall -q .] passed");
    }
}
