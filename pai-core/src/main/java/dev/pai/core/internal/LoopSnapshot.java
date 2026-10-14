/* pai-next — Apache-2.0 */
package dev.pai.core.internal;

import java.nio.file.Path;
import java.util.List;

import dev.pai.core.loop.LoopState;
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
 */
public record LoopSnapshot(List<Message> messages, ModelRef model, Usage usageThisRun, int turnIndex,
    Path cwd) implements LoopState {

    /**
     * Copies the messages.
     */
    public LoopSnapshot {
        messages = List.copyOf(messages);
    }
}
