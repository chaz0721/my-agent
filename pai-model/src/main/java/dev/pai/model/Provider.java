/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.util.Set;
import java.util.function.Consumer;

/**
 * A model API endpoint. Implementations translate {@link ChatRequest} through a codec and stream the reply.
 */
public interface Provider {

    /**
     * Returns the provider id, for example {@code anthropic} or {@code openai-compat}.
     *
     * @return the id
     */
    String id();

    /**
     * Returns the capabilities this provider offers for the given model.
     *
     * @param model the model
     * @return capabilities
     */
    Set<ModelRef.CapabilityEnum> capabilities(ModelRef model);

    /**
     * Streams one reply. Blocks until the stream ends; every event is delivered synchronously on the calling thread.
     * Request and network failures are never thrown: they are encoded as an {@link StreamEvent.Error} event and a
     * returned message with stop reason {@link StopReasonEnum#ERROR}.
     *
     * @param request the request
     * @param sink receives every stream event
     * @param cancel cooperative cancellation
     * @return the final message, identical to the one in the terminal event
     * @throws IllegalArgumentException when the request itself is invalid
     */
    AssistantMessage stream(ChatRequest request, Consumer<StreamEvent> sink, CancelToken cancel);
}
