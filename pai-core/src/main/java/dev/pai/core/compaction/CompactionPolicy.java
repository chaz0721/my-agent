/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.util.List;

import dev.pai.model.AssistantMessage;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.UserMessage;

/**
 * When to compact and where the compacted prefix may end.
 *
 * @param reserveForOutput tokens kept free for the reply
 * @param safetyMargin extra tokens kept free for estimation error
 * @param keepRecentMessages number of most recent messages never summarized
 */
public record CompactionPolicy(int reserveForOutput, int safetyMargin, int keepRecentMessages) {

    /** Default {@link #reserveForOutput()}. */
    public static final int DEFAULT_RESERVE = 20_000;
    /** Default {@link #safetyMargin()}. */
    public static final int DEFAULT_SAFETY_MARGIN = 13_000;
    /** Default {@link #keepRecentMessages()}. */
    public static final int DEFAULT_KEEP_RECENT = 6;

    /** Validates fields. */
    public CompactionPolicy {
        if (reserveForOutput < 0 || safetyMargin < 0 || keepRecentMessages < 0) {
            throw new IllegalArgumentException("policy values must not be negative");
        }
    }

    /**
     * Returns the default policy.
     *
     * @return the policy
     */
    public static CompactionPolicy defaults() {
        return new CompactionPolicy(DEFAULT_RESERVE, DEFAULT_SAFETY_MARGIN, DEFAULT_KEEP_RECENT);
    }

    /**
     * Returns the context size above which compaction starts: {@code max(window - reserve - margin, window / 2)}.
     *
     * @param m the model
     * @return threshold in tokens
     */
    public int thresholdFor(ModelRef m) {
        int window = m.contextWindow();
        return Math.max(window - reserveForOutput - safetyMargin, window / 2);
    }

    /**
     * Chooses where the summarized prefix ends: the largest index {@code k >= 1} with at least
     * {@link #keepRecentMessages()} messages after it at which a new exchange starts, so that no tool call is separated
     * from its result. When {@code force} is set and no such index exists, the smallest boundary inside the recent
     * messages is used instead.
     *
     * @param messages the context
     * @param force whether to accept a boundary inside the recent messages
     * @return {@code k}, or 0 when nothing can be compacted
     */
    public int splitIndex(List<Message> messages, boolean force) {
        int upper = messages.size() - keepRecentMessages;
        for (int k = Math.min(upper, messages.size() - 1); k >= 1; k--) {
            if (isBoundary(messages.get(k))) {
                return k;
            }
        }
        if (force) {
            for (int k = Math.max(1, upper + 1); k < messages.size(); k++) {
                if (isBoundary(messages.get(k))) {
                    return k;
                }
            }
        }
        return 0;
    }

    /** A kept tail may start at a user message or at an assistant reply that calls no tools. */
    private static boolean isBoundary(Message m) {
        return m instanceof UserMessage || m instanceof AssistantMessage a && a.toolCalls().isEmpty();
    }
}
