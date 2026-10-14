/* pai-next — Apache-2.0 */
package dev.pai.core.internal;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.pai.core.loop.LoopState;
import dev.pai.model.CancelToken;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.Usage;

/**
 * Immutable {@link LoopState}.
 *
 * @param messages context snapshot
 * @param model model in use
 * @param usageThisRun usage so far
 * @param turnIndex current turn
 * @param cwd working directory
 * @param contextOverflow whether this is the overflow retry
 * @param cancel the run's cancellation token
 * @param leafEntryId the session entry the context is the projection of
 */
public record LoopSnapshot(List<Message> messages, ModelRef model, Usage usageThisRun, int turnIndex, Path cwd,
    boolean contextOverflow, CancelToken cancel, Optional<String> leafEntryId) implements LoopState {

    /**
     * Copies the messages.
     */
    public LoopSnapshot {
        messages = List.copyOf(messages);
    }

    /**
     * Creates a snapshot without a session leaf.
     *
     * @param messages context snapshot
     * @param model model in use
     * @param usageThisRun usage so far
     * @param turnIndex current turn
     * @param cwd working directory
     * @param contextOverflow whether this is the overflow retry
     * @param cancel the run's cancellation token
     */
    public LoopSnapshot(List<Message> messages, ModelRef model, Usage usageThisRun, int turnIndex, Path cwd,
        boolean contextOverflow, CancelToken cancel) {
        this(messages, model, usageThisRun, turnIndex, cwd, contextOverflow, cancel, Optional.empty());
    }

    /**
     * Creates a snapshot outside an overflow retry, without cancellation.
     *
     * @param messages context snapshot
     * @param model model in use
     * @param usageThisRun usage so far
     * @param turnIndex current turn
     * @param cwd working directory
     */
    public LoopSnapshot(List<Message> messages, ModelRef model, Usage usageThisRun, int turnIndex, Path cwd) {
        this(messages, model, usageThisRun, turnIndex, cwd, false, CancelToken.none());
    }
}
