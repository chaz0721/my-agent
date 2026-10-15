/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.util.List;
import java.util.Optional;

import dev.pai.model.ChatRequest;
import dev.pai.model.Message;

/**
 * A step of the context pipeline the loop runs before every request, after the transformContext hooks (ADR 0003, M7).
 * Closed: only pai-core's compaction stages and test fakes implement it; extensions cannot add stages.
 */
public interface ContextStage {

    /**
     * Returns the name used in logs.
     *
     * @return the name
     */
    String name();

    /**
     * Applies the stage to the previous stage's output; returning any other list than {@code messages} takes effect and
     * makes the loop emit one {@link AgentEvent.ContextCompacted} of {@link #kind()}.
     *
     * @param messages the output of the previous stage
     * @param state the loop snapshot, whose messages are the kept context
     * @return the new list, or {@code messages} itself when there is nothing to do
     */
    List<Message> apply(List<Message> messages, LoopState state);

    /**
     * Returns what a result is: {@code MICRO} is only sent with the request, {@code SUMMARY} replaces the kept context.
     *
     * @return the kind, {@code SUMMARY} by default
     */
    default AgentEvent.ContextCompacted.KindEnum kind() {
        return AgentEvent.ContextCompacted.KindEnum.SUMMARY;
    }

    /**
     * Returns the summary text a result of this stage starts with.
     *
     * @param result a list this stage returned
     * @return the summary, empty by default
     */
    default Optional<String> summaryOf(List<Message> result) {
        return Optional.empty();
    }

    /**
     * Tells the stage the request finally sent (after the prepareRequest hooks and late tools), for calibration.
     *
     * @param request the request handed to the provider
     */
    default void requestSent(ChatRequest request) {}
}
