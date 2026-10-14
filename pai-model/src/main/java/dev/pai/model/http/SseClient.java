/* pai-next — Apache-2.0 */
package dev.pai.model.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.pai.model.CancelToken;

/**
 * Minimal Server-Sent Events client over the JDK HttpClient: POSTs JSON and hands every {@code data:} payload to a
 * callback on the calling thread.
 */
public final class SseClient {

    /** Default time allowed to establish a connection. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30L);
    /** Default time allowed until response headers arrive. */
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(300L);

    private static final Logger log = LoggerFactory.getLogger(SseClient.class);
    private static final String DATA_PREFIX = "data:";
    private static final int ERROR_BODY_LIMIT = 500;
    private static final int HTTP_OK_MIN = 200;
    private static final int HTTP_OK_MAX = 299;

    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final boolean forceHttp1;
    private final boolean debugBodies;
    private final HttpClient http;

    /**
     * Creates a client.
     *
     * @param connectTimeout connection timeout
     * @param responseTimeout timeout until response headers arrive
     * @param forceHttp1 use HTTP/1.1 only (DeepSeek needs this)
     * @param debugBodies log request bodies at INFO (never headers)
     */
    public SseClient(Duration connectTimeout, Duration responseTimeout, boolean forceHttp1, boolean debugBodies) {
        this.connectTimeout = connectTimeout;
        this.responseTimeout = responseTimeout;
        this.forceHttp1 = forceHttp1;
        this.debugBodies = debugBodies;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout)
            .version(forceHttp1 ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /**
     * Creates a client with default timeouts, HTTP/2 negotiation and no body logging.
     */
    public SseClient() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT, false, false);
    }

    /**
     * Returns a copy of this client restricted to HTTP/1.1.
     *
     * @return this when already HTTP/1.1, otherwise a new client
     */
    public SseClient http1() {
        return forceHttp1 ? this : new SseClient(connectTimeout, responseTimeout, true, debugBodies);
    }

    /**
     * POSTs a JSON body and reads the response as SSE, line by line, until the body ends or {@code cancel} fires.
     *
     * @param uri endpoint
     * @param headers request headers (Content-Type is added)
     * @param body JSON bytes
     * @param onDataLine receives each {@code data:} payload without the prefix
     * @param cancel closes the response body when cancelled
     * @throws SseHttpException on a non-2xx status, with the response body
     * @throws IOException on transport failure, or when cancelled mid-stream
     */
    public void postStream(URI uri, Map<String, String> headers, byte[] body, Consumer<String> onDataLine,
        CancelToken cancel) throws IOException {
        HttpRequest.Builder req =
            HttpRequest.newBuilder(uri).timeout(responseTimeout).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(req::header);
        if (debugBodies) {
            log.info("http POST {} body={}", uri, new String(body, StandardCharsets.UTF_8));
        }
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while connecting to " + uri);
        }
        try (InputStream in = resp.body()) {
            cancel.onCancel(() -> closeQuietly(in));
            if (resp.statusCode() < HTTP_OK_MIN || resp.statusCode() > HTTP_OK_MAX) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                throw new SseHttpException(resp.statusCode(), truncate(text));
            }
            readLines(in, onDataLine, cancel);
        }
    }

    private static void readLines(InputStream in, Consumer<String> onDataLine, CancelToken cancel) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while (!cancel.isCancelled() && (line = reader.readLine()) != null) {
            if (line.startsWith(DATA_PREFIX)) {
                String payload = line.substring(DATA_PREFIX.length());
                onDataLine.accept(payload.startsWith(" ") ? payload.substring(1) : payload);
            }
        }
    }

    private static String truncate(String s) {
        return s.length() <= ERROR_BODY_LIMIT ? s : s.substring(0, ERROR_BODY_LIMIT);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            log.debug("closing cancelled stream failed", e);
        }
    }
}
