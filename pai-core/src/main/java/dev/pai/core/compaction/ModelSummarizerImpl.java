/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

import dev.pai.core.loop.LoopState;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.Provider;
import dev.pai.model.SystemMessage;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.UserMessage;

/**
 * Summarizes with one request to the current model: the messages are rendered as a plain transcript (so no tool
 * definitions are needed) and the fixed prompt {@code summarize.md} asks for a structured summary.
 */
public final class ModelSummarizerImpl implements Summarizer {

    /** Upper bound of the summary reply in tokens. */
    public static final int MAX_SUMMARY_TOKENS = 4_096;
    /** Characters of one tool result or tool call kept in the transcript. */
    public static final int MAX_BLOCK_CHARS = 2_000;
    /** Tokens kept free besides the reply when sizing the transcript (prompt, estimation error). */
    public static final int SUMMARY_MARGIN_TOKENS = 2_000;
    /** Smallest transcript budget in characters, for tiny context windows. */
    public static final int MIN_TRANSCRIPT_CHARS = 8_000;

    private static final String PROMPT_RESOURCE = "summarize.md";

    private final Supplier<Provider> provider;
    private final String prompt;

    /**
     * Creates a summarizer.
     *
     * @param provider supplies the provider of the current model (it may change with the model)
     */
    public ModelSummarizerImpl(Supplier<Provider> provider) {
        this.provider = provider;
        this.prompt = resource();
    }

    @Override
    public String summarize(List<Message> toSummarize, LoopState state, CancelToken c) {
        long budgetTokens =
            (long)state.model().contextWindow() - MAX_SUMMARY_TOKENS - SUMMARY_MARGIN_TOKENS - prompt.length() / 4L;
        int budget = (int)Math.max(MIN_TRANSCRIPT_CHARS,
            Math.min(Integer.MAX_VALUE, budgetTokens * CharsTokenEstimatorImpl.CHARS_PER_TOKEN));
        String transcript = transcript(toSummarize, budget);
        UserMessage ask = new UserMessage(
            List.of(new ContentBlock.Text("<conversation>\n" + transcript + "\n</conversation>\n\n" + prompt)),
            Instant.now());
        int maxTokens = Math.min(MAX_SUMMARY_TOKENS, Math.max(1, state.model().maxOutputTokens()));
        ChatOptions options = new ChatOptions(OptionalInt.of(maxTokens), OptionalDouble.empty(),
            ChatOptions.ThinkingLevelEnum.OFF, Map.of());
        ChatRequest request = new ChatRequest(state.model(), Optional.of("You summarize coding sessions for handover."),
            List.of(ask), List.of(), options);
        AssistantMessage reply = provider.get().stream(request, ev -> {
        }, c);
        String text = reply.text().strip();
        if (!reply.replayable() || text.isEmpty()) {
            throw new IllegalStateException(
                "summary request failed: " + reply.errorMessage().orElse(reply.stopReason().name()));
        }
        return text;
    }

    /**
     * Renders messages as a role-tagged transcript of at most about {@code maxChars} characters; thinking is dropped,
     * long blocks are cut, and when the whole does not fit the earliest messages are left out, except a leading summary
     * of an earlier compaction.
     *
     * @param messages the messages
     * @param maxChars character budget
     * @return the transcript
     */
    static String transcript(List<Message> messages, int maxChars) {
        List<String> parts = new ArrayList<>(messages.size());
        for (Message m : messages) {
            parts.add(cut(render(m), maxChars));
        }
        boolean keepHead = !messages.isEmpty() && messages.get(0) instanceof UserMessage u
            && texts(u.content()).startsWith(JsonlSessionStoreImpl.SUMMARY_PREFIX);
        int first = keepHead ? 1 : 0;
        long total = parts.stream().mapToLong(String::length).sum();
        int drop = first;
        while (total > maxChars && drop < parts.size() - 1) {
            total -= parts.get(drop).length();
            drop++;
        }
        StringBuilder sb = new StringBuilder();
        if (keepHead) {
            sb.append(parts.get(0));
        }
        if (drop > first) {
            sb.append("[... ").append(drop - first).append(" earlier messages omitted ...]\n");
        }
        parts.subList(drop, parts.size()).forEach(sb::append);
        return sb.toString().strip();
    }

    private static String render(Message m) {
        StringBuilder sb = new StringBuilder();
        switch (m) {
            case SystemMessage s -> sb.append("[system]: ").append(cut(s.text(), MAX_BLOCK_CHARS)).append('\n');
            case UserMessage u -> sb.append("[user]: ").append(texts(u.content())).append('\n');
            case AssistantMessage a -> {
                if (!a.text().isBlank()) {
                    sb.append("[assistant]: ").append(a.text()).append('\n');
                }
                for (ContentBlock.ToolCall call : a.toolCalls()) {
                    sb.append("[assistant tool call]: ").append(call.name()).append(' ')
                        .append(cut(call.arguments().toString(), MAX_BLOCK_CHARS)).append('\n');
                }
            }
            case ToolResultMessage r ->
                sb.append("[tool result ").append(r.toolName()).append(r.isError() ? " error" : "").append("]: ")
                    .append(cut(texts(r.content()), MAX_BLOCK_CHARS)).append('\n');
        }
        return sb.toString();
    }

    private static String texts(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof ContentBlock.Text t) {
                sb.append(t.text());
            } else if (b instanceof ContentBlock.Image) {
                sb.append("[image]");
            }
        }
        return sb.toString();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + " [... " + (s.length() - max) + " chars omitted]";
    }

    private static String resource() {
        try (InputStream in =
            Objects.requireNonNull(ModelSummarizerImpl.class.getResourceAsStream(PROMPT_RESOURCE), PROMPT_RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
