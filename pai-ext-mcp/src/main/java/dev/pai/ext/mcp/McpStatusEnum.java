/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

/**
 * Lifecycle state of one configured MCP server.
 */
public enum McpStatusEnum {
    /** Connecting, initializing or listing tools. */
    STARTING,
    /** Initialized; its tools are registered. */
    READY,
    /** Start failed or the connection was lost; its tools are unregistered. */
    ERROR,
    /** Stopped on purpose (reload or shutdown). */
    STOPPED
}
