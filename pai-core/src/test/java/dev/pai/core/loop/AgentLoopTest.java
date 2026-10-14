/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.ChatOptions;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopTest {

    private static final ModelRef MODEL = new ModelRef("fake", "fake-model", URI.create("http://fake.invalid"), 1000,
        100, Set.of(ModelRef.CapabilityEnum.TOOLS));

    @TempDir
    Path cwd;

    private final List<AgentEvent> events = new ArrayList<>();
    private final ToolRegistry tools = new ToolRegistry();

    private static ContentBlock.ToolCall call(String id, String name, String path) {
        return new ContentBlock.ToolCall(id, name, Json.mapper().createObjectNode().put("path", path));
    }

    private static AssistantMessage calls(ContentBlock.ToolCall... c) {
        return FakeProviderImpl.reply(List.of(c), StopReasonEnum.TOOL_USE);
    }

    private static AssistantMessage text(String s) {
        return FakeProviderImpl.reply(List.of(new ContentBlock.Text(s)), StopReasonEnum.STOP);
    }

    private List<Message> run(List<AssistantMessage> script, AgentHooks hooks, int maxTurns, CancelToken cancel) {
        AgentConfig cfg = new AgentConfig(MODEL, "sys", ChatOptions.defaults(), 4, maxTurns);
        AgentLoop loop = new AgentLoop(FakeProviderImpl.scripted(script), tools, hooks, cfg, cwd);
        return loop.run(List.of(), UserMessage.text("go"), events::add, cancel);
    }

    private List<Message> run(AssistantMessage... script) {
        return run(List.of(script), new AgentHooks() {}, AgentConfig.DEFAULT_MAX_TURNS, CancelToken.none());
    }

    private <T> List<T> eventsOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static ToolResultMessage result(List<Message> added, int i) {
        return (ToolResultMessage)added.get(i);
    }

    @Test
    void single_turn_without_tools_ends_the_run() {
        List<Message> added = run(text("hello"));
        assertThat(added).hasSize(2);
        assertThat(((AssistantMessage)added.get(1)).text()).isEqualTo("hello");
        assertThat(events.get(0)).isInstanceOf(AgentEvent.AgentStart.class);
        assertThat(events.get(events.size() - 1)).isEqualTo(new AgentEvent.AgentEnd(added));
        assertThat(eventsOf(AgentEvent.TurnStart.class)).hasSize(1);
        assertThat(eventsOf(AgentEvent.MessageUpdate.class)).isNotEmpty();
        assertThat(eventsOf(AgentEvent.Error.class)).isEmpty();
    }

    @Test
    @Timeout(10)
    void read_only_tools_run_in_parallel_and_results_keep_call_order() {
        CountDownLatch bothRunning = new CountDownLatch(2);
        tools.register(new TestTool("slow", ToolEffectEnum.READ_ONLY, (a, c) -> {
            bothRunning.countDown();
            boolean parallel = bothRunning.await(5L, TimeUnit.SECONDS);
            Thread.sleep(100L);
            return ToolResult.text("slow:" + parallel);
        }), "test");
        tools.register(new TestTool("fast", ToolEffectEnum.READ_ONLY, (a, c) -> {
            bothRunning.countDown();
            boolean parallel = bothRunning.await(5L, TimeUnit.SECONDS);
            return ToolResult.text("fast:" + parallel);
        }), "test");
        List<Message> added = run(calls(call("c1", "slow", "a"), call("c2", "fast", "b")), text("done"));
        assertThat(result(added, 2).toolCallId()).isEqualTo("c1");
        assertThat(result(added, 3).toolCallId()).isEqualTo("c2");
        assertThat(((ContentBlock.Text)result(added, 2).content().get(0)).text()).isEqualTo("slow:true");
        assertThat(((ContentBlock.Text)result(added, 3).content().get(0)).text()).isEqualTo("fast:true");
        assertThat(eventsOf(AgentEvent.ToolExecutionEnd.class)).extracting(e -> e.call().id()).containsExactly("c2",
            "c1");
        assertThat(eventsOf(AgentEvent.TurnEnd.class).get(0).toolResults()).extracting(ToolResultMessage::toolCallId)
            .containsExactly("c1", "c2");
    }

    @Test
    void write_tools_run_sequentially() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        TestTool.Body body = (a, c) -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            Thread.sleep(50L);
            running.decrementAndGet();
            return ToolResult.text("ok");
        };
        tools.register(new TestTool("w", ToolEffectEnum.WRITE_FS, body), "test");
        tools.register(new TestTool("r", ToolEffectEnum.READ_ONLY, body), "test");
        run(calls(call("c1", "w", "a"), call("c2", "r", "b"), call("c3", "w", "c")), text("done"));
        assertThat(maxRunning.get()).isEqualTo(1);
        assertThat(eventsOf(AgentEvent.ToolExecutionEnd.class)).extracting(e -> e.call().id()).containsExactly("c1",
            "c2", "c3");
    }

    @Test
    void unknown_tool_is_reported_back_as_error() {
        List<Message> added = run(calls(call("c1", "nope", "a")), text("sorry"));
        ToolResultMessage r = result(added, 2);
        assertThat(r.isError()).isTrue();
        assertThat(((ContentBlock.Text)r.content().get(0)).text()).isEqualTo("unknown tool: nope");
        assertThat(added).hasSize(4);
    }

    @Test
    void blocked_call_is_not_executed_and_reason_is_fed_back() {
        TestTool t = new TestTool("w", ToolEffectEnum.WRITE_FS, (a, c) -> ToolResult.text("ran"));
        tools.register(t, "test");
        AgentHooks block = new AgentHooks() {
            @Override
            public ToolDecision beforeToolCall(ContentBlock.ToolCall c, dev.pai.core.tool.Tool tool, LoopState s) {
                return ToolDecision.block("not allowed in tests");
            }
        };
        List<Message> added = run(List.of(calls(call("c1", "w", "a")), text("ok")), block, 50, CancelToken.none());
        assertThat(t.calls).isEmpty();
        assertThat(result(added, 2).isError()).isTrue();
        assertThat(((ContentBlock.Text)result(added, 2).content().get(0)).text()).contains("not allowed in tests");
    }

    @Test
    void throwing_tool_becomes_error_result() {
        tools.register(new TestTool("boom", ToolEffectEnum.EXECUTE, (a, c) -> {
            throw new java.io.IOException("disk on fire");
        }), "test");
        List<Message> added = run(calls(call("c1", "boom", "a")), text("ok"));
        assertThat(result(added, 2).isError()).isTrue();
        assertThat(((ContentBlock.Text)result(added, 2).content().get(0)).text()).isEqualTo("disk on fire");
    }

    @Test
    void schema_violation_is_reported_without_executing() {
        TestTool t = new TestTool("r", ToolEffectEnum.READ_ONLY, (a, c) -> ToolResult.text("ran"));
        tools.register(t, "test");
        ContentBlock.ToolCall bad =
            new ContentBlock.ToolCall("c1", "r", Json.mapper().createObjectNode().put("path", 3));
        List<Message> added = run(calls(bad), text("ok"));
        assertThat(t.calls).isEmpty();
        assertThat(((ContentBlock.Text)result(added, 2).content().get(0)).text()).contains("path");
    }

    @Test
    void max_turns_stops_the_run_with_error() {
        tools.register(new TestTool("r", ToolEffectEnum.READ_ONLY, (a, c) -> ToolResult.text("again")), "test");
        List<Message> added =
            run(List.of(calls(call("c1", "r", "a")), calls(call("c2", "r", "a")), calls(call("c3", "r", "a"))),
                new AgentHooks() {}, 2, CancelToken.none());
        assertThat(eventsOf(AgentEvent.TurnStart.class)).hasSize(2);
        assertThat(eventsOf(AgentEvent.Error.class)).singleElement()
            .satisfies(e -> assertThat(e.message()).contains("max turns"));
        assertThat(added).hasSize(5);
    }

    @Test
    void cancel_during_tools_aborts_next_turn_and_returns_messages_so_far() {
        CancelTokenSource cancel = new CancelTokenSource();
        tools.register(new TestTool("r", ToolEffectEnum.READ_ONLY, (a, c) -> {
            cancel.cancel();
            return ToolResult.text("partial");
        }), "test");
        List<Message> added = run(List.of(calls(call("c1", "r", "a")), text("never")), new AgentHooks() {}, 50, cancel);
        assertThat(added).hasSize(4);
        AssistantMessage last = (AssistantMessage)added.get(3);
        assertThat(last.stopReason()).isEqualTo(StopReasonEnum.ABORTED);
        assertThat(eventsOf(AgentEvent.Error.class)).hasSize(1);
        assertThat(events.get(events.size() - 1)).isInstanceOf(AgentEvent.AgentEnd.class);
    }

    @Test
    void history_is_sent_and_steering_messages_extend_the_run() {
        List<List<Message>> seen = new ArrayList<>();
        FakeProviderImpl provider = new FakeProviderImpl("fake", req -> {
            seen.add(req.messages());
            return text("reply " + seen.size());
        });
        AtomicInteger steered = new AtomicInteger();
        AgentHooks steer = new AgentHooks() {
            @Override
            public List<Message> steeringMessages() {
                return steered.getAndIncrement() == 0 ? List.of(UserMessage.text("also this")) : List.of();
            }
        };
        AgentLoop loop = new AgentLoop(provider, tools, steer, AgentConfig.of(MODEL, "sys"), cwd);
        List<Message> history = List.of(UserMessage.text("earlier"));
        List<Message> added = loop.run(history, UserMessage.text("now"), events::add, CancelToken.none());
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0)).hasSize(2).first().isEqualTo(history.get(0));
        assertThat(added).hasSize(4);
        assertThat(seen.get(1)).hasSize(4);
    }

    @Test
    void tool_progress_is_relayed_as_update_events() {
        tools.register(new TestTool("p", ToolEffectEnum.EXECUTE, (a, c) -> {
            c.progress("half");
            assertThat(c.cwd()).isEqualTo(cwd);
            return ToolResult.text("done");
        }), "test");
        run(calls(call("c1", "p", "a")), text("ok"));
        assertThat(eventsOf(AgentEvent.ToolExecutionUpdate.class))
            .containsExactly(new AgentEvent.ToolExecutionUpdate("c1", "half"));
    }

    private static AssistantMessage withDiagnostic(AssistantMessage m, String callId, String diagnostic) {
        return new AssistantMessage(m.content(), m.providerId(), m.modelId(), m.usage(), m.stopReason(),
            java.util.Optional.empty(), m.timestamp(), java.util.Optional.of(java.util.Map.of(callId, diagnostic)));
    }

    @Test
    void call_with_invalid_json_diagnostic_is_not_executed_and_diagnostic_is_fed_back() {
        TestTool t = new TestTool("r", ToolEffectEnum.READ_ONLY, (a, c) -> ToolResult.text("ran"));
        tools.register(t, "test");
        ContentBlock.ToolCall bad = new ContentBlock.ToolCall("c1", "r", Json.mapper().createObjectNode());
        ContentBlock.ToolCall good = call("c2", "r", "ok.txt");
        String diag = AssistantMessage.invalidArgumentsDiagnostic("c1", "r", "{\"path\":");
        List<Message> added = run(withDiagnostic(calls(bad, good), "c1", diag), text("fixed"));
        assertThat(t.calls).hasSize(1);
        assertThat(((ContentBlock.Text)result(added, 2).content().get(0)).text()).startsWith(diag);
        assertThat(result(added, 2).isError()).isTrue();
        assertThat(result(added, 3).isError()).isFalse();
    }

    @Test
    void throwing_before_hook_blocks_the_call() {
        TestTool t = new TestTool("r", ToolEffectEnum.READ_ONLY, (a, c) -> ToolResult.text("ran"));
        tools.register(t, "test");
        AgentHooks bad = new AgentHooks() {
            @Override
            public ToolDecision beforeToolCall(ContentBlock.ToolCall c, dev.pai.core.tool.Tool tool, LoopState s) {
                throw new IllegalStateException("hook bug");
            }
        };
        List<Message> added = run(List.of(calls(call("c1", "r", "a")), text("ok")), bad, 50, CancelToken.none());
        assertThat(t.calls).isEmpty();
        assertThat(((ContentBlock.Text)result(added, 2).content().get(0)).text()).contains("hook bug");
    }

    @Test
    void throwing_after_hook_keeps_the_original_result() {
        tools.register(new TestTool("r", ToolEffectEnum.READ_ONLY, (a, c) -> ToolResult.text("original")), "test");
        AgentHooks bad = new AgentHooks() {
            @Override
            public ToolResult afterToolCall(ContentBlock.ToolCall c, ToolResult r, LoopState s) {
                throw new IllegalStateException("hook bug");
            }
        };
        List<Message> added = run(List.of(calls(call("c1", "r", "a")), text("ok")), bad, 50, CancelToken.none());
        assertThat(((ContentBlock.Text)result(added, 2).content().get(0)).text()).isEqualTo("original");
        assertThat(eventsOf(AgentEvent.Error.class)).isEmpty();
    }

    @Test
    void throwing_transform_context_ends_the_run_with_error_and_agent_end() {
        AgentHooks bad = new AgentHooks() {
            @Override
            public List<Message> transformContext(List<Message> m, LoopState s) {
                throw new IllegalStateException("compaction bug");
            }
        };
        List<Message> added = run(List.of(text("never")), bad, 50, CancelToken.none());
        assertThat(added).hasSize(1);
        assertThat(eventsOf(AgentEvent.Error.class)).singleElement()
            .satisfies(e -> assertThat(e.message()).contains("compaction bug"));
        assertThat(events.get(events.size() - 1)).isInstanceOf(AgentEvent.AgentEnd.class);
    }

    @Test
    void sequential_calls_after_cancel_are_not_executed() {
        CancelTokenSource cancel = new CancelTokenSource();
        TestTool first = new TestTool("w1", ToolEffectEnum.WRITE_FS, (a, c) -> {
            cancel.cancel();
            return ToolResult.text("done");
        });
        TestTool second = new TestTool("w2", ToolEffectEnum.WRITE_FS, (a, c) -> ToolResult.text("ran"));
        tools.register(first, "test");
        tools.register(second, "test");
        List<Message> added = run(List.of(calls(call("c1", "w1", "a"), call("c2", "w2", "b")), text("never")),
            new AgentHooks() {}, 50, cancel);
        assertThat(second.calls).isEmpty();
        assertThat(result(added, 3).isError()).isTrue();
        assertThat(((ContentBlock.Text)result(added, 3).content().get(0)).text()).isEqualTo("cancelled");
    }

    @Test
    void tools_are_only_offered_to_models_with_tool_capability() {
        tools.register(new TestTool("r", ToolEffectEnum.READ_ONLY, (a, c) -> ToolResult.text("x")), "test");
        List<Integer> offered = new ArrayList<>();
        FakeProviderImpl provider = new FakeProviderImpl("fake", req -> {
            offered.add(req.tools().size());
            return text("hi");
        });
        ModelRef plain = new ModelRef("fake", "plain", URI.create("http://fake.invalid"), 1000, 100, Set.of());
        new AgentLoop(provider, tools, new AgentHooks() {}, AgentConfig.of(plain, "s"), cwd).run(List.of(),
            UserMessage.text("q"), e -> {
            }, CancelToken.none());
        new AgentLoop(provider, tools, new AgentHooks() {}, AgentConfig.of(MODEL, "s"), cwd).run(List.of(),
            UserMessage.text("q"), e -> {
            }, CancelToken.none());
        assertThat(offered).containsExactly(0, 1);
    }

    @Test
    void throwing_steering_hook_is_treated_as_no_messages() {
        AgentHooks bad = new AgentHooks() {
            @Override
            public List<Message> steeringMessages() {
                throw new IllegalStateException("steering bug");
            }
        };
        List<Message> added = run(List.of(text("hello")), bad, 50, CancelToken.none());
        assertThat(added).hasSize(2);
        assertThat(eventsOf(AgentEvent.Error.class)).isEmpty();
        assertThat(events.get(events.size() - 1)).isInstanceOf(AgentEvent.AgentEnd.class);
    }
}
