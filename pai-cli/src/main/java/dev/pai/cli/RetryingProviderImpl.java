/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatRequest;
import dev.pai.model.ModelRef;
import dev.pai.model.Provider;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.StreamEvent;
import dev.pai.model.http.HttpFailure;

/**
 * A provider that repeats a failed call of another provider: only when the failure is transient (no response, 429, 5xx)
 * and the stream produced no output yet (no text, reasoning or tool call), at most {@link #MAX_RETRIES} times, waiting
 * for the server's {@code Retry-After} or else an exponential backoff with jitter. Events of an attempt are held back
 * until its first output, so a retried attempt leaves no trace in the sink. No circuit breaker, no fallback.
 */
final class RetryingProviderImpl implements Provider {

    /** Retries after the first attempt. */
    static final int MAX_RETRIES = 3;
    /** Backoff before the first retry; doubled for each further one. */
    static final Duration BASE_DELAY = Duration.ofSeconds(1L);
    /** Longest backoff and longest honoured {@code Retry-After}. */
    static final Duration MAX_DELAY = Duration.ofSeconds(60L);

    /** Longest piece of the server's error text shown in a retry notice. */
    static final int MAX_NOTICE_ERROR = 120;

    private static final Logger log = LoggerFactory.getLogger(RetryingProviderImpl.class);
    private static final long PAUSE_STEP_MILLIS = 100L;

    private final Provider inner;
    private final BiConsumer<Duration, CancelToken> pause;
    private final Consumer<String> notices;

    /**
     * Wraps a provider; each retry is announced on {@code notices} (one line for the user).
     */
    RetryingProviderImpl(Provider inner, Consumer<String> notices) {
        this(inner, RetryingProviderImpl::sleep, notices);
    }

    RetryingProviderImpl(Provider inner, BiConsumer<Duration, CancelToken> pause) {
        this(inner, pause, m -> log.info("{}", m));
    }

    RetryingProviderImpl(Provider inner, BiConsumer<Duration, CancelToken> pause, Consumer<String> notices) {
        this.inner = inner;
        this.pause = pause;
        this.notices = notices;
    }

    @Override
    public String id() {
        return inner.id();
    }

    @Override
    public Set<ModelRef.CapabilityEnum> capabilities(ModelRef model) {
        return inner.capabilities(model);
    }

    @Override
    public AssistantMessage stream(ChatRequest request, Consumer<StreamEvent> sink, CancelToken cancel) {
        for (int attempt = 0;; attempt++) {
            Attempt a = new Attempt(sink);
            AssistantMessage reply = inner.stream(request, a, cancel);
            Optional<Duration> delay =
                attempt < MAX_RETRIES && !cancel.isCancelled() ? a.retryDelay(attempt) : Optional.empty();
            if (delay.isEmpty()) {
                a.release();
                return reply;
            }
            String why = a.error.message();
            why = why.length() > MAX_NOTICE_ERROR ? why.substring(0, MAX_NOTICE_ERROR) + "..." : why;
            notices.accept(inner.id() + " call failed (" + why + "); retry " + (attempt + 1) + "/" + MAX_RETRIES
                + " in " + delay.get().toMillis() + " ms");
            pause.accept(delay.get(), cancel);
            if (cancel.isCancelled()) {
                a.release();
                return reply;
            }
        }
    }

    /** Waits, checking the token every {@value #PAUSE_STEP_MILLIS} ms. */
    private static void sleep(Duration d, CancelToken cancel) {
        long end = System.nanoTime() + d.toNanos();
        try {
            while (!cancel.isCancelled() && System.nanoTime() < end) {
                Thread.sleep(Math.min(PAUSE_STEP_MILLIS, Math.max(1L, (end - System.nanoTime()) / 1_000_000L)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Exponential backoff with up to 50 % jitter, capped at {@link #MAX_DELAY}. */
    static Duration backoff(int attempt) {
        long base = BASE_DELAY.toMillis() << attempt;
        long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
        return Duration.ofMillis(Math.min(MAX_DELAY.toMillis(), base + jitter));
    }

    /**
     * Events of one attempt: held until the first output event, then passed straight through.
     */
    private static final class Attempt implements Consumer<StreamEvent> {
        private final Consumer<StreamEvent> sink;
        private final List<StreamEvent> held = new ArrayList<>();
        private boolean passing;
        private StreamEvent.Error error;

        private Attempt(Consumer<StreamEvent> sink) {
            this.sink = sink;
        }

        @Override
        public void accept(StreamEvent ev) {
            if (passing) {
                sink.accept(ev);
                return;
            }
            boolean output = switch (ev) {
                case StreamEvent.TextDelta t -> true;
                case StreamEvent.ThinkingDelta t -> true;
                case StreamEvent.ToolCallStart t -> true;
                case StreamEvent.ToolCallDelta t -> true;
                case StreamEvent.ToolCallEnd t -> true;
                case StreamEvent.Start s -> false;
                case StreamEvent.Done d -> false;
                case StreamEvent.Error e -> {
                    error = e;
                    yield false;
                }
            };
            held.add(ev);
            if (output) {
                release();
            }
        }

        /** Delivers what was held back and passes everything after it through. */
        private void release() {
            passing = true;
            held.forEach(sink);
            held.clear();
        }

        private Optional<Duration> retryDelay(int attempt) {
            if (passing || error == null || error.reason() != StopReasonEnum.ERROR
                || error.partial().map(m -> !m.content().isEmpty()).orElse(false)) {
                return Optional.empty();
            }
            return HttpFailure.parse(error.message()).filter(HttpFailure::transientFailure).map(f -> f.retryAfter()
                .map(d -> d.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : d).orElseGet(() -> backoff(attempt)));
        }
    }
}
