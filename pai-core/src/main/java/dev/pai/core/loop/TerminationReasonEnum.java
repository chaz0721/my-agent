/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

/**
 * Why a run ended ({@link AgentEvent.AgentEnd#reason()}).
 */
public enum TerminationReasonEnum {
    /** The model replied without tool calls and no steering message followed. */
    COMPLETED,
    /** {@link AgentConfig#maxTurns()} turns were used. */
    MAX_TURNS,
    /** The run was cancelled. */
    ABORTED,
    /** The provider rejected the request as longer than the context window, also after the overflow retry. */
    PROMPT_TOO_LONG,
    /** Replies with tool calls were cut off at the output token limit more often than the loop retries. */
    MAX_OUTPUT_TOKENS,
    /** The provider failed for another reason. */
    PROVIDER_ERROR,
    /** The {@code transformContext} hook chain threw. */
    HOOK_ERROR
}
