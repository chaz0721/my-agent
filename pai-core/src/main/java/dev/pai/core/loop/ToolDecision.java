/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.util.Objects;
import java.util.Optional;

/**
 * A hook's verdict on a tool call.
 *
 * @param allowed whether the call may run
 * @param reason why it was blocked
 */
public record ToolDecision(boolean allowed, Optional<String> reason) {

    private static final ToolDecision ALLOW = new ToolDecision(true, Optional.empty());

    /**
     * Validates fields.
     */
    public ToolDecision {
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * Allows the call.
     *
     * @return the decision
     */
    public static ToolDecision allow() {
        return ALLOW;
    }

    /**
     * Blocks the call; the reason is fed back to the model as an error result.
     *
     * @param reason the reason
     * @return the decision
     */
    public static ToolDecision block(String reason) {
        return new ToolDecision(false, Optional.of(reason));
    }
}
