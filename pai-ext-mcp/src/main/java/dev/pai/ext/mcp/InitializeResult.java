/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What a server answered to {@code initialize}.
 *
 * @param protocolVersion the protocol version the server speaks
 * @param serverName server name
 * @param serverVersion server version
 * @param capabilities the server's capabilities object
 * @param instructions usage hints for the model, if the server gives any
 */
public record InitializeResult(String protocolVersion, String serverName, String serverVersion, JsonNode capabilities,
    Optional<String> instructions) {

    /**
     * Returns whether the server offers resources.
     *
     * @return true when the capabilities contain {@code resources}
     */
    public boolean hasResources() {
        return capabilities.has("resources");
    }

    /**
     * Returns whether the server offers tools.
     *
     * @return true when the capabilities contain {@code tools}
     */
    public boolean hasTools() {
        return capabilities.has("tools");
    }
}
