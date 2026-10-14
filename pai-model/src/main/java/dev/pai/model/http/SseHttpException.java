/* pai-next — Apache-2.0 */
package dev.pai.model.http;

import java.io.IOException;

/**
 * A non-2xx HTTP response to a streaming request.
 */
public final class SseHttpException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String body;

    /**
     * Creates the exception.
     *
     * @param status HTTP status code
     * @param body response body, truncated
     */
    public SseHttpException(int status, String body) {
        super("HTTP " + status + ": " + body);
        this.status = status;
        this.body = body;
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
