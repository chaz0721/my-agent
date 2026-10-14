/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.IOException;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Moves JSON-RPC messages to and from one MCP server. Receivers and close listeners run on a transport thread and must
 * not block; register them before the first {@link #send}.
 */
public interface McpTransport extends AutoCloseable {

    /**
     * Sends one message; returns once it is handed to the connection, not when it is answered.
     *
     * @param msg the JSON-RPC message
     * @throws IOException when the connection is closed or broken
     */
    void send(JsonNode msg) throws IOException;

    /**
     * Registers a receiver of every message the server sends.
     *
     * @param c the receiver
     */
    void onReceive(Consumer<JsonNode> c);

    /**
     * Registers a listener called once when the connection ends, whether the server went away or {@link #close()} was
     * called.
     *
     * @param c receives a short reason
     */
    void onClosed(Consumer<String> c);

    /**
     * Ends the connection and releases its resources (for stdio: the server process tree). Idempotent.
     */
    @Override
    void close();
}
