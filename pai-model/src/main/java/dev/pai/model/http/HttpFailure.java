/* pai-next — Apache-2.0 */
package dev.pai.model.http;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Why an HTTP provider call failed, as encoded in the message of its terminal {@link dev.pai.model.StreamEvent.Error}:
 * {@code HTTP <status>[ (retry after <n>s)]: <body>} for an error response, {@code transport failure: <cause>} when no
 * response arrived. Callers such as a retrying provider parse it back with {@link #parse(String)}.
 *
 * @param status HTTP status, or {@link #TRANSPORT} when no response arrived
 * @param retryAfter the server's {@code Retry-After}, when it sent one
 */
public record HttpFailure(int status, Optional<Duration> retryAfter) {

    /** Status of a failure without a response (connection refused, reset, timeout). */
    public static final int TRANSPORT = 0;

    private static final String TRANSPORT_PREFIX = "transport failure: ";
    private static final Pattern RESPONSE = Pattern.compile("^HTTP (\\d{3})(?: \\(retry after (\\d+)s\\))?:");
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR = 500;

    /**
     * Returns whether a fresh attempt may succeed: no response, 429, or a 5xx status.
     *
     * @return true for transient failures
     */
    public boolean transientFailure() {
        return status == TRANSPORT || status == TOO_MANY_REQUESTS || status >= SERVER_ERROR;
    }

    /**
     * Parses an error message produced by the HTTP providers.
     *
     * @param message the message of a terminal error event
     * @return the failure, or empty for messages of other origin (decoding errors, cancellation)
     */
    public static Optional<HttpFailure> parse(String message) {
        if (message.startsWith(TRANSPORT_PREFIX)) {
            return Optional.of(new HttpFailure(TRANSPORT, Optional.empty()));
        }
        Matcher m = RESPONSE.matcher(message);
        if (!m.find()) {
            return Optional.empty();
        }
        Optional<Duration> after = Optional.ofNullable(m.group(2)).map(s -> Duration.ofSeconds(Long.parseLong(s)));
        return Optional.of(new HttpFailure(Integer.parseInt(m.group(1)), after));
    }

    static String describe(SseHttpException e) {
        String after = e.retryAfter().map(d -> " (retry after " + d.toSeconds() + "s)").orElse("");
        return "HTTP " + e.status() + after + ": " + e.body();
    }

    static String describe(IOException e) {
        return TRANSPORT_PREFIX + e;
    }
}
