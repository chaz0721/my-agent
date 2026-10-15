/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.util.List;

import dev.pai.core.loop.LoopState;
import dev.pai.model.CancelToken;
import dev.pai.model.Message;

/** Condenses the early part of a conversation into a summary that replaces it in the context. */
public interface Summarizer {

    /**
     * Summarizes messages.
     *
     * @param toSummarize the messages being replaced, oldest first
     * @param state loop snapshot (model in use)
     * @param c cancellation
     * @return the summary text
     * @throws IllegalStateException when no summary could be produced
     */
    String summarize(List<Message> toSummarize, LoopState state, CancelToken c);
}
