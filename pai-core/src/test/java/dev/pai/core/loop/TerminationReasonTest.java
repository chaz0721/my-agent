/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

/** One run per termination reason, with a scripted provider. */
class TerminationReasonTest {

    private static final ModelRef MODEL = new ModelRef("fake", "fake-model", URI.create("http://fake.invalid"), 1000,
        100, Set.of(ModelRef.CapabilityEnum.TOOLS));

    @TempDir
    Path cwd;

    private final List<AgentEvent> events = new ArrayList<>();
    private final List<ChatRequest> requests = new ArrayList<>();
    private final ToolRegistry tools = new ToolRegistry();

    private static AssistantMessage text(String s) {
        return FakeProviderImpl.reply(List.of(new ContentBlock.Text(s)), StopReasonEnum.STOP);
    }

    private static AssistantMessage calls(StopReasonEnum stop, String id) {
        return FakeProviderImpl.reply(
            List.of(new ContentBlock.ToolCall(id, "t", Json.mapper().createObjectNode().put("path", "a"))), stop);
    }

    private static AssistantMessage failed(StopReasonEnum stop, String msg) {
        return new AssistantMessage(List.of(), "fake", "fake", Usage.ZERO, stop, Optional.of(msg), Instant.EPOCH);
    }

    private TerminationReasonEnum run(AgentHooks hooks, int maxTurns, boolean retry, CancelToken cancel,
        AssistantMessage... replies) {
        if (tools.get("t").isEmpty()) {
            tools.register(new TestTool("t", ToolEffectEnum.READ_ONLY, (a, c) -> ToolResult.text("ok")), "test");
        }
        List<AssistantMessage> script = new ArrayList<>(List.of(replies));
        FakeProviderImpl provider = new FakeProviderImpl("fake", req -> {
            requests.add(req);
            return script.remove(0);
        });
        AgentConfig cfg = new AgentConfig(MODEL, "sys", ChatOptions.defaults(), 4, maxTurns, retry, List.of());
        new AgentLoop(provider, tools, hooks, cfg, cwd).run(List.of(), UserMessage.text("go"), events::add, cancel);
        List<AgentEvent.AgentEnd> ends =
            events.stream().filter(AgentEvent.AgentEnd.class::isInstance).map(AgentEvent.AgentEnd.class::cast).toList();
        assertThat(ends).hasSize(1);
        return ends.get(0).reason();
    }

    private TerminationReasonEnum run(AssistantMessage... replies) {
        return run(new AgentHooks() {}, 10, true, CancelToken.none(), replies);
    }

    @Test
    void a_reply_without_tool_calls_completes() {
        assertThat(run(text("done"))).isEqualTo(TerminationReasonEnum.COMPLETED);
    }

    @Test
    void the_turn_limit_ends_with_max_turns() {
        assertThat(run(new AgentHooks() {}, 1, true, CancelToken.none(), calls(StopReasonEnum.TOOL_USE, "c1")))
            .isEqualTo(TerminationReasonEnum.MAX_TURNS);
    }

    @Test
    void a_cancelled_run_ends_aborted() {
        CancelTokenSource cancel = new CancelTokenSource();
        cancel.cancel();
        assertThat(run(new AgentHooks() {}, 10, true, cancel, failed(StopReasonEnum.ABORTED, "aborted")))
            .isEqualTo(TerminationReasonEnum.ABORTED);
    }

    @Test
    void a_prompt_over_the_window_ends_prompt_too_long_also_after_the_retry() {
        String tooLong = "HTTP 400: prompt is too long: 250000 tokens > 200000 maximum";
        assertThat(run(new AgentHooks() {}, 10, false, CancelToken.none(), failed(StopReasonEnum.ERROR, tooLong)))
            .isEqualTo(TerminationReasonEnum.PROMPT_TOO_LONG);
        events.clear();
        AgentHooks dropping = new AgentHooks() {
            @Override
            public List<Message> transformContext(List<Message> messages, LoopState state) {
                return state.contextOverflow() ? messages.subList(1, messages.size()) : messages;
            }
        };
        assertThat(run(dropping, 10, true, CancelToken.none(), failed(StopReasonEnum.ERROR, tooLong),
            failed(StopReasonEnum.ERROR, tooLong))).isEqualTo(TerminationReasonEnum.PROMPT_TOO_LONG);
    }

    @Test
    void cut_off_tool_calls_are_retried_twice_then_end_with_max_output_tokens() {
        TerminationReasonEnum reason = run(calls(StopReasonEnum.LENGTH, "c1"), calls(StopReasonEnum.LENGTH, "c2"),
            calls(StopReasonEnum.LENGTH, "c3"));
        assertThat(reason).isEqualTo(TerminationReasonEnum.MAX_OUTPUT_TOKENS);
        assertThat(requests).hasSize(3);
        List<Message> last = requests.get(2).messages();
        assertThat(last.stream().filter(UserMessage.class::isInstance).map(m -> ((UserMessage)m).content().toString())
            .filter(t -> t.contains("cut off at the output token limit"))).hasSize(2);
        assertThat(events).filteredOn(AgentEvent.Error.class::isInstance).singleElement().asString()
            .contains("output token limit");
    }

    @Test
    void one_cut_off_reply_is_continued_and_the_run_completes() {
        assertThat(run(calls(StopReasonEnum.LENGTH, "c1"), text("done"))).isEqualTo(TerminationReasonEnum.COMPLETED);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).messages().get(requests.get(1).messages().size() - 1))
            .isInstanceOfSatisfying(UserMessage.class, u -> assertThat(u.content().toString()).contains("Continue"));
    }

    @Test
    void only_cut_offs_in_a_row_count_against_the_retries() {
        TerminationReasonEnum reason = run(calls(StopReasonEnum.LENGTH, "c1"), calls(StopReasonEnum.LENGTH, "c2"),
            calls(StopReasonEnum.TOOL_USE, "c3"), calls(StopReasonEnum.LENGTH, "c4"),
            calls(StopReasonEnum.LENGTH, "c5"), text("done"));
        assertThat(reason).isEqualTo(TerminationReasonEnum.COMPLETED);
        assertThat(requests).hasSize(6);
    }

    @Test
    void any_other_provider_failure_ends_with_provider_error() {
        assertThat(run(failed(StopReasonEnum.ERROR, "HTTP 401: invalid api key")))
            .isEqualTo(TerminationReasonEnum.PROVIDER_ERROR);
    }

    @Test
    void a_failing_transform_context_hook_ends_with_hook_error() {
        AgentHooks broken = new AgentHooks() {
            @Override
            public List<Message> transformContext(List<Message> messages, LoopState state) {
                throw new IllegalStateException("bug");
            }
        };
        assertThat(run(broken, 10, true, CancelToken.none(), text("never sent")))
            .isEqualTo(TerminationReasonEnum.HOOK_ERROR);
        assertThat(requests).isEmpty();
    }

    @Test
    void each_turn_start_carries_the_estimated_request_size_and_old_constructors_keep_defaults() {
        run(calls(StopReasonEnum.TOOL_USE, "c1"), text("done"));
        List<AgentEvent.TurnStart> starts = events.stream().filter(AgentEvent.TurnStart.class::isInstance)
            .map(AgentEvent.TurnStart.class::cast).toList();
        assertThat(starts).extracting(AgentEvent.TurnStart::turnIndex).containsExactly(0, 1);
        assertThat(starts.get(1).estimatedTokens()).isGreaterThan(starts.get(0).estimatedTokens()).isPositive();
        assertThat(new AgentEvent.TurnStart(3).estimatedTokens()).isZero();
        assertThat(new AgentEvent.AgentEnd(List.of()).reason()).isEqualTo(TerminationReasonEnum.COMPLETED);
    }
}
