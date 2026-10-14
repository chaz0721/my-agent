/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Offline provider that replays scripted replies as protocol-conformant stream events. Used by tests and
 * {@code --provider fake}.
 */
public final class FakeProviderImpl implements Provider {

    /** Provider id used by {@link #echo()}. */
    public static final String FAKE_ID = "fake";

    private final String id;
    private final Function<ChatRequest, AssistantMessage> responder;

    /**
     * Creates a provider whose replies are computed from each request.
     *
     * @param id provider id
     * @param responder computes the reply; providerId, modelId and timestamp are filled in by this class
     */
    public FakeProviderImpl(String id, Function<ChatRequest, AssistantMessage> responder) {
        this.id = id;
        this.responder = responder;
    }

    /**
     * Creates a provider that returns the given replies in order, one per request.
     *
     * @param replies the replies
     * @return the provider
     * @throws IllegalStateException from {@code stream} when the script is exhausted
     */
    public static FakeProviderImpl scripted(List<AssistantMessage> replies) {
        Deque<AssistantMessage> queue = new ArrayDeque<>(replies);
        return new FakeProviderImpl(FAKE_ID, req -> {
            synchronized (queue) {
                if (queue.isEmpty()) {
                    throw new IllegalStateException("fake provider script exhausted");
                }
                return queue.removeFirst();
            }
        });
    }

    /**
     * Creates a provider that answers every request with {@code "echo: <last user text>"}.
     *
     * @return the provider
     */
    public static FakeProviderImpl echo() {
        return new FakeProviderImpl(FAKE_ID,
            req -> reply(List.of(new ContentBlock.Text("echo: " + lastUserText(req))), StopReasonEnum.STOP));
    }

    /**
     * Convenience constructor for a scripted reply.
     *
     * @param content reply blocks
     * @param stopReason stop reason
     * @return a message with placeholder ids that {@code stream} replaces
     */
    public static AssistantMessage reply(List<ContentBlock> content, StopReasonEnum stopReason) {
        return new AssistantMessage(content, FAKE_ID, FAKE_ID, Usage.ZERO, stopReason, Optional.empty(), Instant.EPOCH);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<ModelRef.CapabilityEnum> capabilities(ModelRef model) {
        return Set.of(ModelRef.CapabilityEnum.TOOLS);
    }

    @Override
    public AssistantMessage stream(ChatRequest request, Consumer<StreamEvent> sink, CancelToken cancel) {
        sink.accept(new StreamEvent.Start());
        if (cancel.isCancelled()) {
            AssistantMessage aborted = new AssistantMessage(List.of(), id, request.model().modelId(), Usage.ZERO,
                StopReasonEnum.ABORTED, Optional.of("cancelled"), Instant.now());
            sink.accept(new StreamEvent.Error(StopReasonEnum.ABORTED, "cancelled", Optional.of(aborted)));
            return aborted;
        }
        AssistantMessage scripted = responder.apply(request);
        List<ContentBlock> content = scripted.content();
        for (int i = 0; i < content.size(); i++) {
            emitBlock(i, content.get(i), sink);
        }
        AssistantMessage done = new AssistantMessage(content, id, request.model().modelId(), scripted.usage(),
            scripted.stopReason(), scripted.errorMessage(), Instant.now());
        sink.accept(new StreamEvent.Done(done));
        return done;
    }

    private static void emitBlock(int index, ContentBlock block, Consumer<StreamEvent> sink) {
        switch (block) {
            case ContentBlock.Text t -> sink.accept(new StreamEvent.TextDelta(index, t.text()));
            case ContentBlock.Thinking t -> sink.accept(new StreamEvent.ThinkingDelta(index, t.text()));
            case ContentBlock.Image ignored -> {
                // images are never streamed by models
            }
            case ContentBlock.ToolCall c -> {
                sink.accept(new StreamEvent.ToolCallStart(index, c.id(), c.name()));
                sink.accept(new StreamEvent.ToolCallDelta(index, c.arguments().toString()));
                sink.accept(new StreamEvent.ToolCallEnd(index, c));
            }
        }
    }

    private static String lastUserText(ChatRequest req) {
        List<Message> messages = req.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage u) {
                return u.content().stream().filter(ContentBlock.Text.class::isInstance)
                    .map(b -> ((ContentBlock.Text)b).text()).findFirst().orElse("");
            }
        }
        return "";
    }
}
