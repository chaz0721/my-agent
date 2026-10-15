/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import dev.pai.core.loop.ToolDecision;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.model.ContentBlock;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalHooksTest {

    @TempDir
    Path cwd;

    private final List<String> asked = new ArrayList<>();

    private ApprovalHooks hooks(ApprovalModeEnum mode, ApprovalDecisionEnum answer) {
        Approver approver = (call, tool, rendered) -> {
            asked.add(rendered);
            return answer;
        };
        return new ApprovalHooks(new ApprovalHooks.Parts(mode, new PathGuard(cwd, List.of()), CommandGuard.builtin(),
            approver, new CallPreview(cwd)));
    }

    private static ToolDecision decide(ApprovalHooks h, String tool, ToolEffectEnum effect, String args) {
        ContentBlock.ToolCall call = FakeTool.call(tool, args);
        return h.beforeToolCall(call, new FakeTool(tool, effect), null);
    }

    /** mode, effect, answer, whether the call runs, whether the user was asked. */
    static Stream<Arguments> matrix() {
        return Stream.of(
            Arguments.of(ApprovalModeEnum.ASK, ToolEffectEnum.READ_ONLY, ApprovalDecisionEnum.ALLOW_ONCE, true, true),
            Arguments.of(ApprovalModeEnum.ASK, ToolEffectEnum.READ_ONLY, ApprovalDecisionEnum.DENY, false, true),
            Arguments.of(ApprovalModeEnum.ASK, ToolEffectEnum.WRITE_FS, ApprovalDecisionEnum.ALLOW_ONCE, true, true),
            Arguments.of(ApprovalModeEnum.ASK, ToolEffectEnum.UNKNOWN, ApprovalDecisionEnum.DENY, false, true),
            Arguments.of(ApprovalModeEnum.AUTO_READ, ToolEffectEnum.READ_ONLY, ApprovalDecisionEnum.DENY, true, false),
            Arguments.of(ApprovalModeEnum.AUTO_READ, ToolEffectEnum.EXECUTE, ApprovalDecisionEnum.ALLOW_ONCE, true,
                true),
            Arguments.of(ApprovalModeEnum.AUTO_READ, ToolEffectEnum.UNKNOWN, ApprovalDecisionEnum.DENY, false, true),
            Arguments.of(ApprovalModeEnum.YOLO, ToolEffectEnum.UNKNOWN, ApprovalDecisionEnum.DENY, true, false),
            Arguments.of(ApprovalModeEnum.YOLO, ToolEffectEnum.WRITE_FS, ApprovalDecisionEnum.DENY, true, false));
    }

    @ParameterizedTest(name = "{0} {1} answered {2}")
    @MethodSource("matrix")
    void modes_and_answers(ApprovalModeEnum mode, ToolEffectEnum effect, ApprovalDecisionEnum answer, boolean runs,
        boolean asks) {
        ToolDecision d = decide(hooks(mode, answer), "t", effect, "{\"x\":1}");
        assertThat(d.allowed()).isEqualTo(runs);
        assertThat(asked).hasSize(asks ? 1 : 0);
        if (!runs) {
            assertThat(d.reason()).contains(ApprovalHooks.DENIED);
        }
    }

    @Test
    void guard_hits_block_in_every_mode_without_asking() {
        for (ApprovalModeEnum mode : ApprovalModeEnum.values()) {
            ApprovalHooks h = hooks(mode, ApprovalDecisionEnum.ALLOW_ALWAYS);
            ToolDecision cmd = decide(h, "bash", ToolEffectEnum.EXECUTE, "{\"command\":\"rm -rf /\"}");
            ToolDecision path = decide(h, "write", ToolEffectEnum.WRITE_FS, "{\"path\":\"../out.txt\"}");
            assertThat(cmd.allowed()).isFalse();
            assertThat(cmd.reason().orElseThrow()).startsWith(ApprovalHooks.POLICY_PREFIX).contains("R1", "cannot");
            assertThat(path.reason().orElseThrow()).startsWith(ApprovalHooks.POLICY_PREFIX).contains("outside");
        }
        assertThat(asked).isEmpty();
    }

    @Test
    void always_skips_later_questions_for_that_tool_until_reset() {
        ApprovalHooks h = hooks(ApprovalModeEnum.ASK, ApprovalDecisionEnum.ALLOW_ALWAYS);
        assertThat(decide(h, "edit", ToolEffectEnum.WRITE_FS, "{\"x\":1}").allowed()).isTrue();
        assertThat(decide(h, "edit", ToolEffectEnum.WRITE_FS, "{\"x\":2}").allowed()).isTrue();
        assertThat(decide(h, "bash", ToolEffectEnum.EXECUTE, "{\"command\":\"ls\"}").allowed()).isTrue();
        assertThat(asked).hasSize(2);
        assertThat(h.alwaysAllowed()).containsExactly("bash", "edit");
        h.reset();
        decide(h, "edit", ToolEffectEnum.WRITE_FS, "{\"x\":3}");
        assertThat(asked).hasSize(3);
    }

    @Test
    void no_answer_is_a_denial_that_explains_itself() {
        ToolDecision d =
            decide(hooks(ApprovalModeEnum.ASK, ApprovalDecisionEnum.NO_ANSWER), "t", ToolEffectEnum.UNKNOWN, "{}");
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains(ApprovalHooks.NO_ANSWER);
        assertThat(
            new DenyingApproverImpl().ask(FakeTool.call("t", "{}"), new FakeTool("t", ToolEffectEnum.UNKNOWN), ""))
            .isEqualTo(ApprovalDecisionEnum.NO_ANSWER);
    }

    @Test
    void unknown_effects_show_the_complete_arguments() {
        String secretish = "x".repeat(500);
        decide(hooks(ApprovalModeEnum.ASK, ApprovalDecisionEnum.DENY), "mcp__s__get_env", ToolEffectEnum.UNKNOWN,
            "{\"name\":\"" + secretish + "\"}");
        assertThat(asked.get(0)).contains("tool mcp__s__get_env (unknown)", secretish);
    }

    @Test
    void reads_outside_the_working_directory_always_ask_but_are_not_blocked() {
        String outside = "{\"path\":\"../elsewhere.txt\"}";
        ApprovalHooks auto = hooks(ApprovalModeEnum.AUTO_READ, ApprovalDecisionEnum.ALLOW_ALWAYS);
        assertThat(decide(auto, "read", ToolEffectEnum.READ_ONLY, "{\"path\":\"in.txt\"}").allowed()).isTrue();
        assertThat(asked).isEmpty();
        assertThat(decide(auto, "read", ToolEffectEnum.READ_ONLY, outside).allowed()).isTrue();
        assertThat(decide(auto, "read", ToolEffectEnum.READ_ONLY, outside).allowed()).isTrue();
        assertThat(asked).hasSize(2).allSatisfy(r -> assertThat(r).startsWith("note: path outside"));
        ToolDecision denied = decide(hooks(ApprovalModeEnum.AUTO_READ, ApprovalDecisionEnum.DENY), "read",
            ToolEffectEnum.READ_ONLY, outside);
        assertThat(denied.reason()).contains(ApprovalHooks.DENIED);
        assertThat(
            decide(hooks(ApprovalModeEnum.YOLO, ApprovalDecisionEnum.DENY), "read", ToolEffectEnum.READ_ONLY, outside)
                .allowed())
            .isTrue();
    }

    @Test
    void parallel_calls_of_one_tool_ask_once_when_the_first_answer_is_always() throws Exception {
        CountDownLatch firstAsked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger asks = new AtomicInteger();
        Approver slow = (call, tool, rendered) -> {
            asks.incrementAndGet();
            firstAsked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ApprovalDecisionEnum.ALLOW_ALWAYS;
        };
        ApprovalHooks h = new ApprovalHooks(new ApprovalHooks.Parts(ApprovalModeEnum.ASK, new PathGuard(cwd, List.of()),
            CommandGuard.builtin(), slow, new CallPreview(cwd)));
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ToolDecision> a = pool.submit(() -> decide(h, "edit", ToolEffectEnum.WRITE_FS, "{\"x\":1}"));
            assertThat(firstAsked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ToolDecision> b = pool.submit(() -> decide(h, "edit", ToolEffectEnum.WRITE_FS, "{\"x\":2}"));
            Thread.sleep(100);
            release.countDown();
            assertThat(a.get().allowed()).isTrue();
            assertThat(b.get().allowed()).isTrue();
        }
        assertThat(asks.get()).isEqualTo(1);
    }

    @Test
    void only_the_builtin_bash_gets_the_command_view() {
        ApprovalHooks h = hooks(ApprovalModeEnum.ASK, ApprovalDecisionEnum.DENY);
        decide(h, "bash", ToolEffectEnum.EXECUTE, "{\"command\":\"ls\"}");
        decide(h, "mcp__s__run", ToolEffectEnum.UNKNOWN, "{\"command\":\"ls\",\"env\":{\"A\":\"1\"}}");
        assertThat(asked.get(0)).endsWith("$ ls");
        assertThat(asked.get(1)).contains("\"command\" : \"ls\"", "\"A\" : \"1\"").doesNotContain("$ ls");
    }

    @Test
    void control_characters_in_arguments_are_made_visible() {
        decide(hooks(ApprovalModeEnum.ASK, ApprovalDecisionEnum.DENY), "bash", ToolEffectEnum.EXECUTE,
            "{\"command\":\"ls\\r\\u001b[2Kecho safe\"}");
        assertThat(asked.get(0)).contains("$ ls\\x0d\\x1b[2Kecho safe").doesNotContain("\u001b", "\r");
    }
}
