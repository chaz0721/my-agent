/* pai-next — Apache-2.0 */
package dev.pai.model.http;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatRequest;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.StreamEvent;
import dev.pai.model.codec.StreamDecoder;

/**
 * Shared streaming procedure of the HTTP providers: encode, POST, decode, and turn every failure into a terminal
 * {@link StreamEvent.Error}.
 */
final class StreamingCall {

    private final SseClient sse;

    StreamingCall(SseClient sse) {
        this.sse = sse;
    }

    AssistantMessage run(Request call, Consumer<StreamEvent> sink, CancelToken cancel) {
        Objects.requireNonNull(call.request().model(), "model");
        AtomicReference<AssistantMessage> result = new AtomicReference<>();
        Consumer<List<StreamEvent>> emit = events -> events.forEach(ev -> {
            switch (ev) {
                case StreamEvent.Done d -> result.set(d.message());
                case StreamEvent.Error e -> result.set(e.partial().orElseThrow());
                default -> {
                    // non-terminal events only reach the sink
                }
            }
            sink.accept(ev);
        });
        StreamDecoder decoder = call.decoder();
        if (cancel.isCancelled()) {
            emit.accept(decoder.fail(StopReasonEnum.ABORTED, "cancelled"));
            return result.get();
        }
        try {
            byte[] body = call.body().toString().getBytes(StandardCharsets.UTF_8);
            sse.postStream(call.uri(), call.headers(), body, line -> emit.accept(decoder.feed(line)), cancel);
            emit.accept(cancel.isCancelled() ? decoder.fail(StopReasonEnum.ABORTED, "cancelled") : decoder.finish());
        } catch (SseHttpException e) {
            emit.accept(decoder.fail(StopReasonEnum.ERROR, HttpFailure.describe(e)));
        } catch (IOException e) {
            StopReasonEnum reason = cancel.isCancelled() ? StopReasonEnum.ABORTED : StopReasonEnum.ERROR;
            String msg = cancel.isCancelled() ? "cancelled" : HttpFailure.describe(e);
            emit.accept(decoder.fail(reason, msg));
        }
        return result.get();
    }

    /**
     * One prepared HTTP call.
     *
     * @param request the neutral request
     * @param uri endpoint
     * @param headers headers including authentication
     * @param body encoded body
     * @param decoder decoder for the response
     */
    record Request(ChatRequest request, URI uri, Map<String, String> headers, ObjectNode body, StreamDecoder decoder) {
    }

    static URI endpoint(URI prefix, String path) {
        String base = prefix.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }
}
