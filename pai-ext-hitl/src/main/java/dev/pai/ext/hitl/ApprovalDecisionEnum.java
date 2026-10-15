/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

/**
 * The answer to one approval request.
 */
public enum ApprovalDecisionEnum {
    /** Run this call. */
    ALLOW_ONCE,
    /** Run this call and every later call of the same tool in this session. */
    ALLOW_ALWAYS,
    /** Do not run the call. */
    DENY,
    /** Nobody answered (no terminal, end of input, interrupt); treated as a denial. */
    NO_ANSWER
}
