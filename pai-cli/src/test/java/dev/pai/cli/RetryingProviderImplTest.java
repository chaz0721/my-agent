/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.ModelRef;
import dev.pai.model.Provider;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.StreamEvent;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;
import dev.pai.model.http.OpenAiCompatProviderImpl;
import dev.pai.model.http.SseClient;
import dev.pai.model.preset.ProviderFlags;

import static org.assertj.core.api.Assertions.assertThat;

class RetryingProviderImplTest {

    private static final ModelRef MODEL =
        new ModelRef("x", "m", URI.create("http://unused.invalid"), 1000, 100, Set.of());
    private static final ChatRequest REQUEST =
        new ChatRequest(MODEL, Optional.empty(), List.of(UserMessage.text("hi")), List.of(), ChatOptions.defaults());

    private final List<Duration> pauses = new ArrayList<>();

    private static AssistantMessage message(StopReasonEnum stop, String error, ContentBlock... content) {
        return new AssistantMessage(List.of(content), "x", "m", Usage.ZERO, stop,
            error.isEmpty() ? Optional.empty() : Optional.of(error), Instant.EPOCH);
    }

    private static List<StreamEvent> failure(StopReasonEnum reason, String error, ContentBlock... partial) {
        List<StreamEvent> out = new ArrayList<>();
        out.add(new StreamEvent.Start());
        if (partial.length > 0) {
            out.add(new StreamEvent.TextDelta(0, ((ContentBlock.Text)partial[0]).text()));
        }
        out.add(new StreamEvent.Error(reason, error, Optional.of(message(reason, error, partial))));
        return out;
    }

    private static List<StreamEvent> success(String text) {
        return List.of(new StreamEvent.Start(), new StreamEvent.TextDelta(0, text),
            new StreamEvent.Done(message(StopReasonEnum.STOP, "", new ContentBlock.Text(text))));
    }

    /** A provider that plays one scripted stream per call. */
    private static Provider scripted(Deque<List<StreamEvent>> scripts, AtomicInteger calls) {
        return new Provider() {
            @Override
            public String id() {
                return "x";
            }

            @Override
            public Set<ModelRef.CapabilityEnum> capabilities(ModelRef model) {
                return model.capabilities();
            }

            @Override
            public AssistantMessage stream(ChatRequest request, Consumer<StreamEvent> sink, CancelToken cancel) {
                calls.incrementAndGet();
                List<StreamEvent> events = scripts.removeFirst();
                events.forEach(sink);
                StreamEvent last = events.get(events.size() - 1);
                return last instanceof StreamEvent.Done d ? d.message()
                    : ((StreamEvent.Error)last).partial().orElseThrow();
            }
        };
    }

    private RetryingProviderImpl retrying(Provider p) {
        return new RetryingProviderImpl(p, (d, c) -> pauses.add(d));
    }

    @Test
    void transient_failure_before_any_output_is_retried_and_leaves_no_trace() {
        AtomicInteger calls = new AtomicInteger();
        Deque<List<StreamEvent>> scripts = new ArrayDeque<>(List.of(failure(StopReasonEnum.ERROR, "HTTP 429: slow"),
            failure(StopReasonEnum.ERROR, "transport failure: java.net.ConnectException"), success("ok")));
        List<StreamEvent> seen = new ArrayList<>();
        AssistantMessage m = retrying(scripted(scripts, calls)).stream(REQUEST, seen::add, CancelToken.none());
        assertThat(m.text()).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(seen).extracting(e -> e.getClass().getSimpleName()).containsExactly("Start", "TextDelta", "Done");
        assertThat(pauses).hasSize(2);
        assertThat(pauses.get(0)).isBetween(Duration.ofMillis(1000L), Duration.ofMillis(1500L));
        assertThat(pauses.get(1)).isBetween(Duration.ofMillis(2000L), Duration.ofMillis(3000L));
    }

    @Test
    void retry_after_is_respected_and_capped() {
        AtomicInteger calls = new AtomicInteger();
        Deque<List<StreamEvent>> scripts =
            new ArrayDeque<>(List.of(failure(StopReasonEnum.ERROR, "HTTP 503 (retry after 7s): busy"),
                failure(StopReasonEnum.ERROR, "HTTP 429 (retry after 3600s): later"), success("ok")));
        retrying(scripted(scripts, calls)).stream(REQUEST, e -> {
        }, CancelToken.none());
        assertThat(pauses).containsExactly(Duration.ofSeconds(7L), RetryingProviderImpl.MAX_DELAY);
    }

    @Test
    void failure_after_partial_output_is_not_retried() {
        AtomicInteger calls = new AtomicInteger();
        Deque<List<StreamEvent>> scripts = new ArrayDeque<>(
            List.of(failure(StopReasonEnum.ERROR, "transport failure: reset", new ContentBlock.Text("half"))));
        List<StreamEvent> seen = new ArrayList<>();
        AssistantMessage m = retrying(scripted(scripts, calls)).stream(REQUEST, seen::add, CancelToken.none());
        assertThat(calls).hasValue(1);
        assertThat(m.text()).isEqualTo("half");
        assertThat(seen).extracting(e -> e.getClass().getSimpleName()).containsExactly("Start", "TextDelta", "Error");
        assertThat(pauses).isEmpty();
    }

    @Test
    void client_errors_aborts_and_unknown_errors_are_not_retried() {
        for (List<StreamEvent> script : List.of(failure(StopReasonEnum.ERROR, "HTTP 400: bad request"),
            failure(StopReasonEnum.ERROR, "HTTP 401: invalid key"), failure(StopReasonEnum.ABORTED, "cancelled"),
            failure(StopReasonEnum.ERROR, "stream ended before completion"))) {
            AtomicInteger calls = new AtomicInteger();
            List<StreamEvent> seen = new ArrayList<>();
            retrying(scripted(new ArrayDeque<>(List.of(script)), calls)).stream(REQUEST, seen::add, CancelToken.none());
            assertThat(calls).hasValue(1);
            assertThat(seen).hasSize(2).last().isInstanceOf(StreamEvent.Error.class);
        }
        assertThat(pauses).isEmpty();
    }

    @Test
    void gives_up_after_three_retries_with_the_last_error() {
        AtomicInteger calls = new AtomicInteger();
        Deque<List<StreamEvent>> scripts = new ArrayDeque<>();
        for (int i = 0; i < 5; i++) {
            scripts.add(failure(StopReasonEnum.ERROR, "HTTP 500: boom " + i));
        }
        List<StreamEvent> seen = new ArrayList<>();
        AssistantMessage m = retrying(scripted(scripts, calls)).stream(REQUEST, seen::add, CancelToken.none());
        assertThat(calls).hasValue(1 + RetryingProviderImpl.MAX_RETRIES);
        assertThat(m.errorMessage()).hasValue("HTTP 500: boom 3");
        assertThat(seen).hasSize(2);
    }

    @Test
    void cancel_during_the_pause_stops_retrying() {
        AtomicInteger calls = new AtomicInteger();
        CancelTokenSource cancel = new CancelTokenSource();
        Deque<List<StreamEvent>> scripts =
            new ArrayDeque<>(List.of(failure(StopReasonEnum.ERROR, "HTTP 429: slow"), success("never")));
        RetryingProviderImpl p = new RetryingProviderImpl(scripted(scripts, calls), (d, c) -> cancel.cancel());
        AssistantMessage m = p.stream(REQUEST, e -> {
        }, cancel);
        assertThat(calls).hasValue(1);
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.ERROR);
    }

    @Test
    void real_http_429_then_200_succeeds() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            boolean first = hits.incrementAndGet() == 1;
            byte[] body = (first ? "{\"error\":\"rate limited\"}"
                : "data: {\"choices\":[{\"delta\":{\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
            if (first) {
                ex.getResponseHeaders().add("Retry-After", "0");
            }
            ex.sendResponseHeaders(first ? 429 : 200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            Provider p = new OpenAiCompatProviderImpl("x", "k", base, new SseClient(), ProviderFlags.NONE);
            AssistantMessage m = retrying(p).stream(REQUEST, e -> {
            }, CancelToken.none());
            assertThat(m.text()).isEqualTo("pong");
            assertThat(hits).hasValue(2);
            assertThat(pauses).containsExactly(Duration.ZERO);
        } finally {
            server.stop(0);
        }
    }
}
