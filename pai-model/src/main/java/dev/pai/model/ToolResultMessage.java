/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The result of one tool call, fed back to the model.
 *
 * @param toolCallId id of the tool call this answers
 * @param toolName name of the tool that ran
 * @param content result blocks (Text or Image)
 * @param isError whether the tool failed
 * @param timestamp completion time
 */
public record ToolResultMessage(String toolCallId, String toolName, List<ContentBlock> content, boolean isError,
    Instant timestamp) implements Message {

    /**
     * Copies the content and validates fields.
     */
    public ToolResultMessage {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(toolName, "toolName");
        content = List.copyOf(content);
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
