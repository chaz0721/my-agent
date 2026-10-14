/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.ContentBlock;

/**
 * What a tool returns: content for the model, an error flag and structured details for hooks and renderers.
 *
 * @param content blocks sent to the model (Text or Image)
 * @param isError whether the call failed
 * @param details structured data not sent to the model
 */
public record ToolResult(List<ContentBlock> content, boolean isError, Optional<JsonNode> details) {

    /**
     * Copies the content and validates fields.
     */
    public ToolResult {
        content = List.copyOf(content);
        Objects.requireNonNull(details, "details");
    }

    /**
     * Creates a successful text result.
     *
     * @param s the text
     * @return the result
     */
    public static ToolResult text(String s) {
        return new ToolResult(List.of(new ContentBlock.Text(s)), false, Optional.empty());
    }

    /**
     * Creates an error result.
     *
     * @param s the error text shown to the model
     * @return the result
     */
    public static ToolResult error(String s) {
        return new ToolResult(List.of(new ContentBlock.Text(s)), true, Optional.empty());
    }

    /**
     * Returns a copy carrying the given details.
     *
     * @param d the details
     * @return the new result
     */
    public ToolResult withDetails(JsonNode d) {
        return new ToolResult(content, isError, Optional.of(d));
    }
}
