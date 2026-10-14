/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.Json;

/**
 * MCP Streamable HTTP: every message is a POST to the endpoint; the reply is empty (202), a JSON body or an SSE stream
 * of messages. The {@code Mcp-Session-Id} the server assigns is sent with every later request and the protocol version
 * negotiated by {@code initialize} as {@code MCP-Protocol-Version}; closing sends DELETE for the session. A request is
 * posted on its own virtual thread; notifications and responses are posted on the calling thread, so they reach the
 * server before anything sent after them; they time out after the server's {@code timeoutMs} (10 s when unset). An HTTP
 * or connection failure of a request becomes a JSON-RPC error response for its id. Redirects are not followed, so
 * headers such as {@code Authorization} never travel to another host. The optional server-initiated GET stream is not
 * opened, so server notifications arrive only inside POST responses.
 */
public final class StreamableHttpTransportImpl implements McpTransport {

    /** Header carrying the session id. */
    public static final String SESSION_HEADER = "Mcp-Session-Id";
    /** Header carrying the negotiated protocol version. */
    public static final String VERSION_HEADER = "MCP-Protocol-Version";

    private static final Logger log = LoggerFactory.getLogger(StreamableHttpTransportImpl.class);
    private static final int HTTP_ACCEPTED = 202;
    private static final int HTTP_NO_CONTENT = 204;
    private static final int HTTP_ERROR = 400;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int MAX_ERROR_BODY_CHARS = 500;
    private static final int TRANSPORT_ERROR = -32000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DELETE_TIMEOUT = Duration.ofSeconds(2);
    /** Timeout of synchronous posts (notifications, responses) when the server sets no timeout of its own. */
    static final Duration DEFAULT_SYNC_TIMEOUT = Duration.ofSeconds(10);

    private final String name;
    private final URI url;
    private final Map<String, String> headers;
    private final Duration syncTimeout;
    private final HttpClient http;
    private final List<Consumer<JsonNode>> receivers = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> closeListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeFired = new AtomicBoolean();
    private volatile Optional<String> sessionId = Optional.empty();
    private volatile Optional<String> protocolVersion = Optional.empty();

    /**
     * Creates the transport; nothing is sent until {@link #send}.
     *
     * @param cfg endpoint and extra headers
     */
    public StreamableHttpTransportImpl(McpServerConfig.Http cfg) {
        this.name = cfg.name();
        this.url = cfg.url();
        this.headers = cfg.headers();
        this.syncTimeout = cfg.timeout().equals(McpConfig.DEFAULT_TIMEOUT) ? DEFAULT_SYNC_TIMEOUT : cfg.timeout();
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Returns the session id the server assigned, if any.
     *
     * @return the session id
     */
    public Optional<String> sessionId() {
        return sessionId;
    }

    @Override
    public void send(JsonNode msg) throws IOException {
        if (closed.get()) {
            throw new IOException("transport closed");
        }
        String body = Json.mapper().writeValueAsString(msg);
        HttpRequest.Builder b =
            request().header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (msg.has("id") && msg.has("method")) {
            HttpRequest req = b.build();
            Thread.ofVirtual().name("pai-mcp-" + name + "-post").start(() -> post(req, msg));
        } else {
            post(b.timeout(syncTimeout).build(), msg);
        }
    }

    @Override
    public void onReceive(Consumer<JsonNode> c) {
        receivers.add(c);
    }

    @Override
    public void onClosed(Consumer<String> c) {
        closeListeners.add(c);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (sessionId.isPresent()) {
            try {
                http.send(request().timeout(DELETE_TIMEOUT).DELETE().build(), HttpResponse.BodyHandlers.discarding());
            } catch (IOException e) {
                log.debug("DELETE of session {} on {} failed", sessionId.get(), name, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        http.shutdownNow();
        fireClosed("closed");
    }

    private HttpRequest.Builder request() {
        HttpRequest.Builder b = HttpRequest.newBuilder(url);
        headers.forEach(b::header);
        sessionId.ifPresent(id -> b.header(SESSION_HEADER, id));
        protocolVersion.ifPresent(v -> b.header(VERSION_HEADER, v));
        return b;
    }

    private void post(HttpRequest req, JsonNode msg) {
        try {
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            r.headers().firstValue(SESSION_HEADER).ifPresent(id -> sessionId = Optional.of(id));
            try (InputStream in = r.body()) {
                handle(r, in, msg);
            }
        } catch (IOException e) {
            if (!closed.get()) {
                fail(msg, "connection failed: " + e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(msg, "interrupted");
        }
    }

    private void handle(HttpResponse<InputStream> r, InputStream in, JsonNode msg) throws IOException {
        int status = r.statusCode();
        if (status == HTTP_ACCEPTED || status == HTTP_NO_CONTENT) {
            return;
        }
        if (status >= HTTP_ERROR) {
            String text = new String(in.readNBytes(MAX_ERROR_BODY_CHARS), StandardCharsets.UTF_8).strip();
            fail(msg, "HTTP " + status + (text.isEmpty() ? "" : ": " + text));
            if (status == HTTP_NOT_FOUND && sessionId.isPresent()) {
                fireClosed("session expired");
            }
            return;
        }
        String type = r.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (type.startsWith("text/event-stream")) {
            readEvents(in);
        } else if (type.startsWith("application/json")) {
            deliverText(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } else {
            fail(msg, "unexpected content type: " + type);
        }
    }

    /** Reads SSE events; the data of each event is one JSON-RPC message (or a batch array). */
    private void readEvents(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                deliverText(data.toString());
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(
                    line.startsWith("data: ") ? line.substring("data: ".length()) : line.substring("data:".length()));
            }
        }
        deliverText(data.toString());
    }

    private void deliverText(String text) {
        if (text.isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = Json.mapper().readTree(text);
        } catch (JsonProcessingException e) {
            log.debug("{} sent invalid JSON", name);
            return;
        }
        if (node.isArray()) {
            node.forEach(this::deliver);
        } else {
            deliver(node);
        }
    }

    private void deliver(JsonNode msg) {
        JsonNode version = msg.path("result").path("protocolVersion");
        if (version.isTextual() && msg.path("result").has("serverInfo")) {
            protocolVersion = Optional.of(version.asText());
        }
        for (Consumer<JsonNode> c : receivers) {
            try {
                c.accept(msg);
            } catch (RuntimeException e) {
                log.warn("receiver of {} failed", name, e);
            }
        }
    }

    /** Turns a transport failure of a request into an error response; failures of notifications are only logged. */
    private void fail(JsonNode msg, String reason) {
        JsonNode id = msg.get("id");
        if (id == null || !msg.has("method")) {
            log.debug("message to {} failed: {}", name, reason);
            return;
        }
        deliver(JsonRpc.encode(JsonRpc.Response.failure(id, TRANSPORT_ERROR, reason)));
    }

    private void fireClosed(String reason) {
        if (!closeFired.compareAndSet(false, true)) {
            return;
        }
        for (Consumer<String> c : closeListeners) {
            try {
                c.accept(reason);
            } catch (RuntimeException e) {
                log.warn("close listener of {} failed", name, e);
            }
        }
    }
}
