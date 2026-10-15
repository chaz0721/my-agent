/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.util.Set;

/**
 * Which early tool results {@link MicroCompactor} replaces with a placeholder.
 *
 * @param keepRecentTurns number of most recent assistant turns whose tool results are never trimmed
 * @param maxToolResultChars text length above which an early tool result is trimmed
 * @param neverTrim names of tools whose results are never trimmed
 */
public record MicroCompactionPolicy(int keepRecentTurns, int maxToolResultChars, Set<String> neverTrim) {

    /** Default {@link #keepRecentTurns()}. */
    public static final int DEFAULT_KEEP_RECENT_TURNS = 4;
    /** Default {@link #maxToolResultChars()}. */
    public static final int DEFAULT_MAX_TOOL_RESULT_CHARS = 2_000;

    /** Validates fields and copies the set. */
    public MicroCompactionPolicy {
        if (keepRecentTurns < 0 || maxToolResultChars < 0) {
            throw new IllegalArgumentException("policy values must not be negative");
        }
        neverTrim = Set.copyOf(neverTrim);
    }

    /**
     * Returns the default policy.
     *
     * @return the policy
     */
    public static MicroCompactionPolicy defaults() {
        return new MicroCompactionPolicy(DEFAULT_KEEP_RECENT_TURNS, DEFAULT_MAX_TOOL_RESULT_CHARS, Set.of());
    }
}
