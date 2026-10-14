/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The result of {@code resources/read}.
 *
 * @param contents content items, each with {@code uri} and {@code text} or {@code blob}
 */
public record ReadResourceResult(List<JsonNode> contents) {

    /**
     * Copies the contents.
     */
    public ReadResourceResult {
        contents = List.copyOf(contents);
    }
}
