/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.pai.model.CancelToken;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Streamable HTTP against a JDK {@link HttpServer} that serves {@link FakeMcpServer}: JSON replies, SSE replies for
 * {@code tools/list}, 202 for notifications, a session id that later requests must carry, DELETE on close.
 */
@Timeout(30)
class StreamableHttpTransportImplTest {

    static final String SESSION = "session-42";

    private HttpServer http;
    private final List<String> log = new CopyOnWriteArrayList<>();
    private volatile int forcedStatus;

    /** Starts the mock server; returns its endpoint. */
    URI start() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/mcp", this::handle);
        http.start();
        return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/mcp");
    }

    @AfterEach
    void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String session = ex.getRequestHeaders().getFirst(StreamableHttpTransportImpl.SESSION_HEADER);
        String version = ex.getRequestHeaders().getFirst(StreamableHttpTransportImpl.VERSION_HEADER);
        if ("DELETE".equals(ex.getRequestMethod())) {
            log.add("DELETE " + session);
            respond(ex, 200, "", "");
            return;
        }
        JsonNode msg = Json.mapper().readTree(ex.getRequestBody());
        String method = msg.path("method").asText();
        log.add(method + " session=" + session + " version=" + version);
        if (forcedStatus != 0) {
            respond(ex, forcedStatus, "text/plain", "server broke");
            return;
        }
        if (!"initialize".equals(method) && !SESSION.equals(session)) {
            respond(ex, 400, "text/plain", "missing session");
            return;
        }
        List<JsonNode> out = new ArrayList<>();
        new FakeMcpServer(out::add).receive(msg);
        if (out.isEmpty()) {
            respond(ex, 202, "", "");
            return;
        }
        if ("initialize".equals(method)) {
            ex.getResponseHeaders().add(StreamableHttpTransportImpl.SESSION_HEADER, SESSION);
        }
        if ("tools/list".equals(method) || "tools/call".equals(method)) {
            StringBuilder sse = new StringBuilder(": comment\n\n");
            for (JsonNode n : out) {
                sse.append("event: message\ndata: ").append(n).append("\n\n");
            }
            respond(ex, 200, "text/event-stream", sse.toString());
        } else {
            respond(ex, 200, "application/json", out.get(0).toString());
        }
    }

    private static void respond(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (!type.isEmpty()) {
            ex.getResponseHeaders().add("Content-Type", type);
        }
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private McpClient client(URI url) {
        McpClient c = new McpClient(
            new StreamableHttpTransportImpl(
                new McpServerConfig.Http("remote", url, Map.of("X-Test", "1"), Duration.ofSeconds(10))),
            Duration.ofSeconds(10));
        c.initialize(new ClientInfo("test", "1"));
        return c;
    }

    @Test
    void session_id_json_and_sse_replies() throws Exception {
        McpClient c = client(start());
        assertThat(c.listTools()).extracting(McpToolDescriptor::name).contains("echo", "changed");
        CallToolResult r =
            c.callTool("echo", Json.mapper().createObjectNode().put("text", "over http"), CancelToken.none());
        assertThat(r.content().get(0).path("text").asText()).isEqualTo("over http");
        c.close();
        assertThat(log.get(0)).isEqualTo("initialize session=null version=null");
        assertThat(log).contains(
            "notifications/initialized session=" + SESSION + " version=" + McpClient.PROTOCOL_VERSION,
            "tools/list session=" + SESSION + " version=" + McpClient.PROTOCOL_VERSION, "DELETE " + SESSION);
    }

    @Test
    void notifications_inside_an_sse_reply_are_delivered() throws Exception {
        McpClient c = client(start());
        CompletableFuture<JsonNode> changed = new CompletableFuture<>();
        c.onNotification(McpServerManager.TOOLS_CHANGED, changed::complete);
        c.callTool("changed", Json.mapper().createObjectNode(), CancelToken.none());
        assertThat(changed.get(5, TimeUnit.SECONDS)).isNotNull();
        c.close();
    }

    @Test
    void http_errors_fail_the_request_with_the_status() throws Exception {
        McpClient c = client(start());
        forcedStatus = 500;
        assertThatThrownBy(c::listTools).isInstanceOf(McpException.class).hasMessageContaining("HTTP 500")
            .hasMessageContaining("server broke");
        c.close();
    }

    @Test
    void connection_refused_fails_the_request() throws Exception {
        URI url = start();
        http.stop(0);
        http = null;
        McpClient c = new McpClient(
            new StreamableHttpTransportImpl(new McpServerConfig.Http("gone", url, Map.of(), Duration.ofSeconds(5))),
            Duration.ofSeconds(5));
        assertThatThrownBy(() -> c.initialize(new ClientInfo("t", "1"))).isInstanceOf(McpException.class)
            .hasMessageContaining("connection failed");
        c.close();
    }
}
