/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.model.AssistantMessage;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class PlanModeTest {

    private static final ModelRef MODEL =
        new ModelRef("fake", "m", URI.create("http://fake.invalid"), 1000, 100, Set.of(ModelRef.CapabilityEnum.TOOLS));
    private static final Map<String,
        Tool> TOOLS = Map.of("read", new FakeTool("read", ToolEffectEnum.READ_ONLY), "write",
            new FakeTool("write", ToolEffectEnum.WRITE_FS), "bash", new FakeTool("bash", ToolEffectEnum.EXECUTE),
            "mcp__x__y", new FakeTool("mcp__x__y", ToolEffectEnum.UNKNOWN), "todo_write",
            new FakeTool("todo_write", ToolEffectEnum.READ_ONLY));

    @TempDir
    Path cwd;

    private final ApprovalHooks hooks =
        new ApprovalHooks(new ApprovalHooks.Parts(ApprovalModeEnum.AUTO_READ, new PathGuard(Path.of("."), List.of()),
            CommandGuard.builtin(), (c, t, r) -> ApprovalDecisionEnum.DENY, new CallPreview(Path.of("."))));

    private PlanMode plan() {
        return new PlanMode(hooks, n -> Optional.ofNullable(TOOLS.get(n)), cwd);
    }

    private static ChatRequest request() {
        List<ToolDefinition> defs =
            TOOLS.keySet().stream().sorted().map(n -> new ToolDefinition(n, "d", JsonSchema.object().build())).toList();
        return new ChatRequest(MODEL, Optional.of("base"), List.of(UserMessage.text("hi")), defs,
            ChatOptions.defaults());
    }

    private static AssistantMessage reply(String text) {
        return new AssistantMessage(List.of(new ContentBlock.Text(text)), "fake", "m", Usage.ZERO, StopReasonEnum.STOP,
            Optional.empty(), Instant.now());
    }

    private static List<String> toolNames(ChatRequest r) {
        return r.tools().stream().map(ToolDefinition::name).toList();
    }

    @Test
    void plan_mode_keeps_only_read_only_tools_and_bash_and_says_so() {
        PlanMode p = plan();
        p.enter();
        ChatRequest r = p.prepareRequest(request(), null);
        assertThat(toolNames(r)).containsExactly("bash", "read", "todo_write");
        assertThat(r.systemPrompt().orElseThrow()).startsWith("base\n\n").contains(PlanMode.SECTION);
        p.leave();
        assertThat(toolNames(p.prepareRequest(request(), null))).hasSize(TOOLS.size());
    }

    @Test
    void leaving_returns_to_the_mode_before() {
        hooks.mode(ApprovalModeEnum.ASK);
        PlanMode p = plan();
        p.enter();
        assertThat(hooks.mode()).isEqualTo(ApprovalModeEnum.PLAN);
        assertThat(p.leave()).isEqualTo(ApprovalModeEnum.ASK);
    }

    @Test
    void a_saved_plan_is_injected_once_in_the_next_normal_run() throws IOException {
        PlanMode p = plan();
        assertThat(p.save()).isEmpty();
        p.enter();
        p.replied(reply("looking around"));
        p.replied(reply("1. edit A\n2. test"));
        assertThat(p.save()).contains(cwd.resolve(PlanMode.PLAN_FILE));
        assertThat(Files.readString(cwd.resolve(PlanMode.PLAN_FILE))).isEqualTo("1. edit A\n2. test\n");
        assertThat(p.prepareRequest(request(), null).systemPrompt().orElseThrow()).doesNotContain("<plan>");
        p.leave();
        String first = p.prepareRequest(request(), null).systemPrompt().orElseThrow();
        String sameRun = p.prepareRequest(request(), null).systemPrompt().orElseThrow();
        p.runEnded();
        String nextRun = p.prepareRequest(request(), null).systemPrompt().orElseThrow();
        assertThat(first).contains(PlanMode.PLAN_OPEN + "1. edit A\n2. test" + PlanMode.PLAN_CLOSE);
        assertThat(sameRun).isEqualTo(first);
        assertThat(nextRun).isEqualTo("base");
    }

    @Test
    void replies_outside_plan_mode_are_not_plans() throws IOException {
        PlanMode p = plan();
        p.replied(reply("done"));
        assertThat(p.save()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ls -la", "cat pom.xml", "git status", "git log --oneline -5", "git diff HEAD~1",
        "rg -n foo src", "grep -rn bar . | head -20", "find . -name *.java", "wc -l a.txt", "tree src", "dir",
        "type README.md", "git show HEAD:pom.xml", "tree -L 2 src"})
    void read_only_commands_pass(String command) {
        assertThat(ReadOnlyCommands.readOnly(command, false)).isTrue();
        assertThat(ReadOnlyCommands.isReadOnlyBash(FakeTool.call("bash", "{\"command\":\"" + command + "\"}")))
            .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rm a.txt", "cat a > b", "ls; rm x", "ls && touch y", "cat $(echo a)", "echo `id`",
        "find . -delete", "find . -exec rm {} +", "rg --pre ./x foo", "git diff --output=x", "tree -o out.txt src",
        "git commit -m x", "git -c core.pager=x log", "mvn test", "ls\nrm x", "sed -i s/a/b/ f", "", "ls || rm x",
        "cat a | sh", "find . -name '*.java' '-delete'", "find . \\-delete", "find . -de''lete", "rg '--pre' sh x",
        "git diff '--output=x'", "tree -ao out.txt", "tree -R -H . -L 1", "rg --hostname-bin=x foo", "grep \"a;b\" f"})
    void anything_else_is_refused(String command) {
        assertThat(ReadOnlyCommands.readOnly(command, false)).isFalse();
        assertThat(ReadOnlyCommands.readOnly(command, true)).isFalse();
    }

    @Test
    void percent_and_caret_are_refused_where_the_shell_may_be_cmd() {
        assertThat(ReadOnlyCommands.readOnly("type %USERPROFILE%\\x", true)).isFalse();
        assertThat(ReadOnlyCommands.readOnly("dir ^& del x", true)).isFalse();
        assertThat(ReadOnlyCommands.readOnly("git log --format=%h -3", true)).isFalse();
        assertThat(ReadOnlyCommands.readOnly("git log --format=%h -3", false)).isTrue();
    }

    @Test
    void paths_that_may_leave_the_working_directory_are_detected() {
        for (String c : List.of("cat ~/x", "cat /etc/passwd", "ls ..", "grep -r key ../other", "type C:x",
            "ls %HOME%")) {
            assertThat(ReadOnlyCommands.mayLeaveCwd(FakeTool.call("bash", "{\"command\":\"" + c + "\"}"))).as(c)
                .isTrue();
        }
        for (String c : List.of("ls -R . | head", "cat src/a.txt", "git show HEAD:pom.xml", "rg -n foo src/main")) {
            assertThat(ReadOnlyCommands.mayLeaveCwd(FakeTool.call("bash", "{\"command\":\"" + c + "\"}"))).as(c)
                .isFalse();
        }
    }

    @Test
    void in_plan_mode_reading_outside_asks_every_time_and_always_is_not_kept() {
        List<String> asked = new java.util.ArrayList<>();
        ApprovalDecisionEnum[] answer = {ApprovalDecisionEnum.DENY};
        ApprovalHooks h = new ApprovalHooks(new ApprovalHooks.Parts(ApprovalModeEnum.PLAN,
            new PathGuard(cwd, List.of()), CommandGuard.builtin(), (c, t, r) -> {
                asked.add(r);
                return answer[0];
            }, new CallPreview(cwd)));
        ContentBlock.ToolCall home = FakeTool.call("bash", "{\"command\":\"cat ~/x\"}");
        assertThat(h.beforeToolCall(home, TOOLS.get("bash"), null).allowed()).isFalse();
        answer[0] = ApprovalDecisionEnum.ALLOW_ALWAYS;
        assertThat(h.beforeToolCall(home, TOOLS.get("bash"), null).allowed()).isTrue();
        answer[0] = ApprovalDecisionEnum.NO_ANSWER;
        assertThat(h.beforeToolCall(home, TOOLS.get("bash"), null).allowed()).isFalse();
        assertThat(asked).hasSize(3).first().asString().startsWith(ApprovalHooks.PLAN_OUTSIDE_NOTE);
        assertThat(h.beforeToolCall(FakeTool.call("bash", "{\"command\":\"ls -R . | head\"}"), TOOLS.get("bash"), null)
            .allowed()).isTrue();
        assertThat(asked).hasSize(3);
    }

    @Test
    void only_a_final_reply_is_kept_as_the_plan() throws IOException {
        PlanMode p = plan();
        p.enter();
        p.replied(new AssistantMessage(
            List.of(new ContentBlock.Text("let me look"), FakeTool.call("read", "{\"path\":\"a\"}")), "fake", "m",
            Usage.ZERO, StopReasonEnum.TOOL_USE, Optional.empty(), Instant.now()));
        p.replied(new AssistantMessage(List.of(new ContentBlock.Text("cut off")), "fake", "m", Usage.ZERO,
            StopReasonEnum.LENGTH, Optional.empty(), Instant.now()));
        assertThat(p.save()).isEmpty();
    }

    @Test
    void only_the_bash_tool_counts() {
        assertThat(ReadOnlyCommands.isReadOnlyBash(FakeTool.call("mcp__x__bash", "{\"command\":\"ls\"}"))).isFalse();
        assertThat(ReadOnlyCommands.isReadOnlyBash(FakeTool.call("bash", "{}"))).isFalse();
    }

    @Test
    void in_plan_mode_whitelisted_bash_runs_without_asking_and_writes_are_blocked() {
        hooks.mode(ApprovalModeEnum.PLAN);
        assertThat(hooks.beforeToolCall(FakeTool.call("bash", "{\"command\":\"git status\"}"), TOOLS.get("bash"), null)
            .allowed()).isTrue();
        assertThat(
            hooks.beforeToolCall(FakeTool.call("bash", "{\"command\":\"mvn test\"}"), TOOLS.get("bash"), null).reason())
            .contains(ApprovalHooks.PLAN_BLOCK);
        assertThat(hooks.beforeToolCall(FakeTool.call("write", "{\"path\":\"a\"}"), TOOLS.get("write"), null).reason())
            .contains(ApprovalHooks.PLAN_BLOCK);
        assertThat(hooks.beforeToolCall(FakeTool.call("mcp__x__y", "{}"), TOOLS.get("mcp__x__y"), null).allowed())
            .isFalse();
        assertThat(hooks.beforeToolCall(FakeTool.call("read", "{\"path\":\"a\"}"), TOOLS.get("read"), null).allowed())
            .isTrue();
    }

    @Test
    void always_answers_do_not_open_plan_mode() {
        ApprovalHooks h = new ApprovalHooks(new ApprovalHooks.Parts(ApprovalModeEnum.ASK, new PathGuard(cwd, List.of()),
            CommandGuard.builtin(), (c, t, r) -> ApprovalDecisionEnum.ALLOW_ALWAYS, new CallPreview(cwd)));
        assertThat(h.beforeToolCall(FakeTool.call("write", "{\"path\":\"a\"}"), TOOLS.get("write"), null).allowed())
            .isTrue();
        h.mode(ApprovalModeEnum.PLAN);
        assertThat(h.beforeToolCall(FakeTool.call("write", "{\"path\":\"a\"}"), TOOLS.get("write"), null).allowed())
            .isFalse();
    }
}
