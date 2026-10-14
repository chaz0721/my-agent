/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

/**
 * A failed MCP request: an error response from the server, a timeout, cancellation or a closed connection.
 */
public final class McpException extends RuntimeException {

    /** {@link #code()} of failures that did not come from the server. */
    public static final int LOCAL = 0;

    private static final long serialVersionUID = 1L;

    private final int code;

    /**
     * Creates a local failure.
     *
     * @param message what failed
     */
    public McpException(String message) {
        this(LOCAL, message);
    }

    /**
     * Creates a failure with a JSON-RPC error code.
     *
     * @param code the server's error code, or {@link #LOCAL}
     * @param message what failed
     */
    public McpException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Creates a local failure with a cause.
     *
     * @param message what failed
     * @param cause the cause
     */
    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.code = LOCAL;
    }

    /**
     * Returns the JSON-RPC error code.
     *
     * @return the code, {@link #LOCAL} for local failures
     */
    public int code() {
        return code;
    }
}
