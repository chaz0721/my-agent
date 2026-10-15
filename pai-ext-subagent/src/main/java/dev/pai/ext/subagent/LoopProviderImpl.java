/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.loop.AgentConfig;
import dev.pai.core.loop.AgentEvent;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatRequest;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.Provider;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.StreamEvent;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

/**
 * The provider a sub-agent's own compaction summarizes with: each request runs as a one-turn, tool-less sub-loop of the
 * host, since an extension never holds the host's provider itself. Only requests that end with a user message are
 * supported, which is what the summarizer sends.
 */
final class LoopProviderImpl implements Provider {

    private final ExtensionContext ctx;

    LoopProviderImpl(ExtensionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "subagent-loop";
    }

    @Override
    public Set<ModelRef.CapabilityEnum> capabilities(ModelRef model) {
        return model.capabilities();
    }

    @Override
    public AssistantMessage stream(ChatRequest request, Consumer<StreamEvent> sink, CancelToken cancel) {
        List<Message> messages = request.messages();
        if (messages.isEmpty() || !(messages.get(messages.size() - 1) instanceof UserMessage prompt)) {
            return failed(request, "a sub-loop request must end with a user message");
        }
        AgentConfig config = new AgentConfig(request.model(), request.systemPrompt().orElse(""), request.options(), 1,
            1, false, List.of());
        Consumer<AgentEvent> events = ev -> {
            if (ev instanceof AgentEvent.MessageUpdate u) {
                sink.accept(u.event());
            }
        };
        List<Message> added =
            ctx.subLoop(config, t -> false).run(messages.subList(0, messages.size() - 1), prompt, events, cancel);
        return added.stream().filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
            .reduce((a, b) -> b).orElseGet(() -> failed(request, "the sub-loop gave no reply"));
    }

    private AssistantMessage failed(ChatRequest request, String why) {
        return new AssistantMessage(List.of(), id(), request.model().modelId(), Usage.ZERO, StopReasonEnum.ERROR,
            Optional.of(why), Instant.now());
    }
}
