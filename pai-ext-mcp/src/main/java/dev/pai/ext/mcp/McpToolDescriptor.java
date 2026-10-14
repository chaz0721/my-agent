/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool a server lists.
 *
 * @param name the server's tool name
 * @param description what it does, possibly empty
 * @param inputSchema JSON Schema of its arguments, exactly as the server sent it
 */
public record McpToolDescriptor(String name, String description, JsonNode inputSchema) {
}
