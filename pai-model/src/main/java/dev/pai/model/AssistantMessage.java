/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A complete model reply: text, thinking and tool call blocks plus usage and stop reason.
 *
 * @param content content blocks (Text, Thinking or ToolCall)
 * @param providerId provider that produced the message
 * @param modelId model that produced the message
 * @param usage token usage of the request
 * @param stopReason why generation stopped
 * @param errorMessage diagnostic when the stop reason is an error or a block had to be repaired
 * @param timestamp completion time
 */
public record AssistantMessage(List<ContentBlock> content, String providerId, String modelId, Usage usage,
    StopReasonEnum stopReason, Optional<String> errorMessage, Instant timestamp) implements Message {

    /**
     * Copies the content and normalizes absent optionals.
     */
    public AssistantMessage {
        content = List.copyOf(content);
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(stopReason, "stopReason");
        errorMessage = errorMessage == null ? Optional.empty() : errorMessage;
        Objects.requireNonNull(timestamp, "timestamp");
    }

    /**
     * Returns the tool call blocks in order of appearance.
     *
     * @return tool calls, possibly empty
     */
    public List<ContentBlock.ToolCall> toolCalls() {
        return content.stream().filter(ContentBlock.ToolCall.class::isInstance).map(ContentBlock.ToolCall.class::cast)
            .toList();
    }

    /**
     * Returns all text blocks concatenated.
     *
     * @return the visible text
     */
    public String text() {
        return content.stream().filter(ContentBlock.Text.class::isInstance).map(b -> ((ContentBlock.Text)b).text())
            .collect(Collectors.joining());
    }
}
