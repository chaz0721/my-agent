/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

/**
 * How the client introduces itself in {@code initialize}.
 *
 * @param name client name
 * @param version client version
 */
public record ClientInfo(String name, String version) {
}
