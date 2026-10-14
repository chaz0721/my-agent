/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The result of {@code tools/call}.
 *
 * @param content the content items (text, image, audio, resource, resource_link)
 * @param isError whether the tool reported a failure
 * @param structuredContent structured output, if any
 */
public record CallToolResult(List<JsonNode> content, boolean isError, Optional<JsonNode> structuredContent) {

    /**
     * Copies the content.
     */
    public CallToolResult {
        content = List.copyOf(content);
    }
}
