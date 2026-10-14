/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.nio.file.Path;
import java.util.List;

import dev.pai.model.CancelToken;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.Usage;

/**
 * Read-only snapshot of a running loop, handed to hooks.
 */
public interface LoopState {

    /**
     * Returns the current context.
     *
     * @return messages, immutable
     */
    List<Message> messages();

    /**
     * Returns the model in use.
     *
     * @return the model
     */
    ModelRef model();

    /**
     * Returns usage accumulated by this run.
     *
     * @return usage
     */
    Usage usageThisRun();

    /**
     * Returns the zero-based turn index.
     *
     * @return turn index
     */
    int turnIndex();

    /**
     * Returns the working directory.
     *
     * @return the cwd
     */
    Path cwd();

    /**
     * Returns whether the previous request of this turn was rejected for exceeding the context window; hooks that
     * compact should then compact regardless of their threshold.
     *
     * @return true on the single overflow retry
     */
    default boolean contextOverflow() {
        return false;
    }

    /**
     * Returns the cancellation token of the run, for hooks that do slow work such as summarizing.
     *
     * @return the token
     */
    default CancelToken cancel() {
        return CancelToken.none();
    }
}
