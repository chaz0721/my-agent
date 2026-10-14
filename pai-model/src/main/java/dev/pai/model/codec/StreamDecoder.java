/* pai-next — Apache-2.0 */
package dev.pai.model.codec;

import java.util.List;

import dev.pai.model.StopReasonEnum;
import dev.pai.model.StreamEvent;

/**
 * Stateful wire decoder of one streaming reply: feed SSE data payloads, receive {@link StreamEvent}s. Every decoder
 * emits exactly one terminal event, from {@link #finish()}, {@link #fail} or a terminal wire event.
 */
public interface StreamDecoder {

    /**
     * Consumes one SSE data payload (with or without the {@code data:} prefix).
     *
     * @param sseDataLine the payload
     * @return events produced, possibly empty
     */
    List<StreamEvent> feed(String sseDataLine);

    /**
     * Signals end of input; emits the terminal event unless one was already emitted.
     *
     * @return remaining events
     */
    List<StreamEvent> finish();

    /**
     * Terminates the stream with an error carrying the partial message, unless already terminated.
     *
     * @param reason ERROR or ABORTED
     * @param message diagnostic
     * @return the terminal event, or nothing when already terminated
     */
    List<StreamEvent> fail(StopReasonEnum reason, String message);
}
