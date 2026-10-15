/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.util.ArrayList;
import java.util.List;

import dev.pai.core.loop.LoopState;
import dev.pai.model.CancelToken;
import dev.pai.model.Message;

/**
 * Summarizer for tests: records its input and returns a fixed text, or fails.
 */
final class FakeSummarizerImpl implements Summarizer {

    final List<List<Message>> calls = new ArrayList<>();
    boolean fail;

    FakeSummarizerImpl(boolean fail) {
        this.fail = fail;
    }

    @Override
    public String summarize(List<Message> toSummarize, LoopState state, CancelToken c) {
        calls.add(toSummarize);
        if (fail) {
            throw new IllegalStateException("model error");
        }
        return "summary of " + toSummarize.size();
    }
}
