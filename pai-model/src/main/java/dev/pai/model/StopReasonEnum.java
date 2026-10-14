/* pai-next — Apache-2.0 */
package dev.pai.model;

/**
 * Why a model stopped generating.
 */
public enum StopReasonEnum {
    /** Natural end of the reply. */
    STOP,
    /** Output token limit reached. */
    LENGTH,
    /** The reply ends with tool calls that must be executed. */
    TOOL_USE,
    /** The request or stream failed. */
    ERROR,
    /** The caller cancelled the request. */
    ABORTED
}
