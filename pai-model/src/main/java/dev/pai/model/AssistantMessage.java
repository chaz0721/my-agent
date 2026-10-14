/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * @param errorMessage diagnostic when the stop reason is an error
 * @param timestamp completion time
 * @param toolCallDiagnostics per tool call id, why a call had to be repaired (for example invalid JSON arguments)
 */
public record AssistantMessage(List<ContentBlock> content, String providerId, String modelId, Usage usage,
    StopReasonEnum stopReason, Optional<String> errorMessage, Instant timestamp,
    Optional<Map<String, String>> toolCallDiagnostics) implements Message {

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
        Map<String, String> d = toolCallDiagnostics == null ? Map.of() : toolCallDiagnostics.orElse(Map.of());
        toolCallDiagnostics = d.isEmpty() ? Optional.empty() : Optional.of(Map.copyOf(d));
    }

    /**
     * Creates a message without tool call diagnostics.
     *
     * @param content content blocks
     * @param providerId provider id
     * @param modelId model id
     * @param usage usage
     * @param stopReason stop reason
     * @param errorMessage error diagnostic
     * @param timestamp completion time
     */
    public AssistantMessage(List<ContentBlock> content, String providerId, String modelId, Usage usage,
        StopReasonEnum stopReason, Optional<String> errorMessage, Instant timestamp) {
        this(content, providerId, modelId, usage, stopReason, errorMessage, timestamp, Optional.empty());
    }

    /** Prefix of every per-tool-call diagnostic text. */
    public static final String TOOL_CALL_DIAGNOSTIC_PREFIX = "tool call ";

    private static final int DIAGNOSTIC_RAW_CHARS = 200;

    /**
     * Formats the diagnostic a codec records in {@link #toolCallDiagnostics()} when a tool call's arguments are not a
     * JSON object.
     *
     * @param toolCallId the call id
     * @param toolName the tool name
     * @param rawArguments the unparseable arguments text
     * @return one diagnostic line
     */
    public static String invalidArgumentsDiagnostic(String toolCallId, String toolName, String rawArguments) {
        String raw = rawArguments.replace('\n', ' ').replace('\r', ' ');
        if (raw.length() > DIAGNOSTIC_RAW_CHARS) {
            raw = raw.substring(0, DIAGNOSTIC_RAW_CHARS) + "...";
        }
        return TOOL_CALL_DIAGNOSTIC_PREFIX + toolCallId + ": invalid JSON arguments for " + toolName + ": " + raw;
    }

    /**
     * Returns the diagnostic recorded for one tool call, if any.
     *
     * @param toolCallId the call id
     * @return the diagnostic
     */
    public Optional<String> toolCallDiagnostic(String toolCallId) {
        return toolCallDiagnostics.map(d -> d.get(toolCallId));
    }

    /**
     * Returns whether this message may be sent back to a model: failed or cancelled replies may hold tool calls that
     * never got results.
     *
     * @return false for ERROR and ABORTED replies
     */
    public boolean replayable() {
        return stopReason != StopReasonEnum.ERROR && stopReason != StopReasonEnum.ABORTED;
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
