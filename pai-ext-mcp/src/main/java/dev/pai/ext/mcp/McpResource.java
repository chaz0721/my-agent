/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

/**
 * A resource a server lists.
 *
 * @param uri resource URI
 * @param name display name
 * @param description description, possibly empty
 * @param mimeType MIME type, possibly empty
 */
public record McpResource(String uri, String name, String description, String mimeType) {
}
