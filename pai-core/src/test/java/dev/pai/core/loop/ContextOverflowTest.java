/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.tool.ToolRegistry;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class ContextOverflowTest {

    private static final ModelRef MODEL = new ModelRef("fake", "fake-model", URI.create("http://fake.invalid"), 1000,
        100, Set.of(ModelRef.CapabilityEnum.TOOLS));
    private static final String TOO_LONG = "HTTP 400: prompt is too long: 250000 tokens > 200000 maximum";

    @TempDir
    Path cwd;

    private final List<AgentEvent> events = new ArrayList<>();
    private final List<ChatRequest> requests = new ArrayList<>();

    private static AssistantMessage error(String msg) {
        return new AssistantMessage(List.of(), "fake", "fake", Usage.ZERO, StopReasonEnum.ERROR, Optional.of(msg),
            Instant.EPOCH);
    }

    private static AssistantMessage text(String s) {
        return FakeProviderImpl.reply(List.of(new ContentBlock.Text(s)), StopReasonEnum.STOP);
    }

    /** Drops the oldest message whenever the loop reports an overflow. */
    private static final class DropOnOverflow implements AgentHooks {
        private final AtomicInteger forced = new AtomicInteger();
        private final boolean canDrop;

        private DropOnOverflow(boolean canDrop) {
            this.canDrop = canDrop;
        }

        @Override
        public List<Message> transformContext(List<Message> messages, LoopState state) {
            if (state.contextOverflow()) {
                forced.incrementAndGet();
                return canDrop ? messages.subList(1, messages.size()) : messages;
            }
            return messages;
        }
    }

    private List<Message> run(AgentHooks hooks, boolean retry, AssistantMessage... replies) {
        List<AssistantMessage> script = new ArrayList<>(List.of(replies));
        FakeProviderImpl provider = new FakeProviderImpl("fake", req -> {
            requests.add(req);
            return script.remove(0);
        });
        AgentConfig cfg = new AgentConfig(MODEL, "sys", ChatOptions.defaults(), 4, 10, retry);
        List<Message> history = List.of(UserMessage.text("old"), text("old reply"));
        return new AgentLoop(provider, new ToolRegistry(), hooks, cfg, cwd).run(history, UserMessage.text("go"),
            events::add, CancelToken.none());
    }

    private <T> List<T> eventsOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void overflow_is_retried_once_after_forced_compaction() {
        DropOnOverflow hooks = new DropOnOverflow(true);
        List<Message> added = run(hooks, true, error(TOO_LONG), text("fine now"));
        assertThat(hooks.forced).hasValue(1);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).messages()).hasSize(requests.get(0).messages().size());
        assertThat(eventsOf(AgentEvent.ContextCompacted.class)).containsExactly(new AgentEvent.ContextCompacted(4, 3));
        assertThat(eventsOf(AgentEvent.Error.class)).isEmpty();
        assertThat(((AssistantMessage)added.get(added.size() - 1)).text()).isEqualTo("fine now");
    }

    @Test
    void second_overflow_ends_the_run_with_an_error() {
        DropOnOverflow hooks = new DropOnOverflow(true);
        run(hooks, true, error(TOO_LONG), error(TOO_LONG));
        assertThat(requests).hasSize(2);
        assertThat(hooks.forced).hasValue(1);
        assertThat(eventsOf(AgentEvent.Error.class)).singleElement()
            .satisfies(e -> assertThat(e.message()).contains("too long"));
    }

    @Test
    void no_retry_when_compaction_frees_nothing_disabled_or_other_errors() {
        run(new DropOnOverflow(false), true, error(TOO_LONG));
        assertThat(requests).hasSize(1);
        assertThat(eventsOf(AgentEvent.Error.class)).hasSize(1);
        requests.clear();
        run(new DropOnOverflow(true), false, error(TOO_LONG));
        assertThat(requests).hasSize(1);
        requests.clear();
        DropOnOverflow hooks = new DropOnOverflow(true);
        run(hooks, true, error("HTTP 401: invalid api key"));
        assertThat(requests).hasSize(1);
        assertThat(hooks.forced).hasValue(0);
        requests.clear();
        DropOnOverflow outputSide = new DropOnOverflow(true);
        run(outputSide, true, error("HTTP 400: max_tokens: 300000 > 64000, which is the maximum allowed number of "
            + "output tokens for claude-sonnet-4-5"));
        assertThat(requests).hasSize(1);
        assertThat(outputSide.forced).hasValue(0);
        requests.clear();
        DropOnOverflow deepseek = new DropOnOverflow(true);
        run(deepseek, true, error("HTTP 400: This model's maximum context length is 65536 tokens."), text("ok"));
        assertThat(deepseek.forced).hasValue(1);
    }

    @Test
    void prepare_request_rewrites_only_the_request_and_a_failing_hook_is_skipped() {
        AgentHooks hooks = AgentHooks.chain(List.of(new AgentHooks() {
            @Override
            public ChatRequest prepareRequest(ChatRequest r, LoopState s) {
                return new ChatRequest(r.model(), Optional.of(r.systemPrompt().orElse("") + "\n# Extra"), r.messages(),
                    r.tools(), r.options());
            }
        }, new AgentHooks() {
            @Override
            public ChatRequest prepareRequest(ChatRequest r, LoopState s) {
                throw new IllegalStateException("bug");
            }
        }));
        run(hooks, true, text("ok"));
        assertThat(requests.get(0).systemPrompt()).contains("sys\n# Extra");
        requests.clear();
        run(AgentHooks.chain(List.of(new AgentHooks() {
            @Override
            public ChatRequest prepareRequest(ChatRequest r, LoopState s) {
                return new ChatRequest(r.model(), Optional.of("sys\n# Extra"), r.messages(), r.tools(), r.options());
            }
        })), true, text("ok"));
        assertThat(requests.get(0).systemPrompt()).contains("sys\n# Extra");
    }
}
