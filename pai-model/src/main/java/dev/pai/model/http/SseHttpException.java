/* pai-next — Apache-2.0 */
package dev.pai.model.http;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * A non-2xx HTTP response to a streaming request.
 */
public final class SseHttpException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String body;
    private final transient Optional<Duration> retryAfter;

    /**
     * Creates the exception.
     *
     * @param status HTTP status code
     * @param body response body, truncated
     */
    public SseHttpException(int status, String body) {
        this(status, body, Optional.empty());
    }

    /**
     * Creates the exception with the server's {@code Retry-After}.
     *
     * @param status HTTP status code
     * @param body response body, truncated
     * @param retryAfter the delay the server asked for
     */
    public SseHttpException(int status, String body, Optional<Duration> retryAfter) {
        super("HTTP " + status + ": " + body);
        this.status = status;
        this.body = body;
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the delay the server asked for with {@code Retry-After}.
     *
     * @return the delay, if the response had the header in seconds or as a date
     */
    public Optional<Duration> retryAfter() {
        return retryAfter;
    }

    /**
     * Returns the HTTP status code.
     *
     * @return status
     */
    public int status() {
        return status;
    }

    /**
     * Returns the (truncated) response body.
     *
     * @return body text
     */
    public String body() {
        return body;
    }
}
