/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

/**
 * Side-effect category of a tool.
 */
public enum ToolEffectEnum {
    /** Reads state only; safe to run concurrently. */
    READ_ONLY,
    /** Writes to the file system. */
    WRITE_FS,
    /** Executes processes. */
    EXECUTE,
    /** Talks to the network. */
    NETWORK,
    /** Effect not declared (for example MCP tools). */
    UNKNOWN
}
