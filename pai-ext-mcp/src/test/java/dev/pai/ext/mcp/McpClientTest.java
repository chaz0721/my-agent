/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;

import dev.pai.model.CancelToken;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(20)
class McpClientTest {

    private final InMemoryTransportImpl transport = new InMemoryTransportImpl();
    private final FakeMcpServer server = transport.server;

    private McpClient client(Duration timeout) {
        McpClient c = new McpClient(transport, timeout);
        c.initialize(new ClientInfo("test", "1"));
        return c;
    }

    private static JsonNode text(String t) {
        return Json.mapper().createObjectNode().put("text", t);
    }

    private List<String> methodsReceived() {
        return server.received.stream().map(m -> m.path("method").asText()).toList();
    }

    @Test
    void initialize_handshake() {
        McpClient c = new McpClient(transport, Duration.ofSeconds(5));
        InitializeResult r = c.initialize(new ClientInfo("test", "1"));
        assertThat(r.serverName()).isEqualTo("fake");
        assertThat(r.protocolVersion()).isEqualTo(McpClient.PROTOCOL_VERSION);
        assertThat(r.hasResources()).isFalse();
        JsonNode init = server.received.get(0);
        assertThat(init.path("params").path("clientInfo").path("name").asText()).isEqualTo("test");
        awaitMethod("notifications/initialized");
    }

    @Test
    void list_tools_follows_cursors() {
        server.tools.addAll(List.of("t1", "t2", "t3"));
        server.pageSize = 2;
        List<McpToolDescriptor> tools = client(Duration.ofSeconds(5)).listTools();
        assertThat(tools).extracting(McpToolDescriptor::name).containsExactly("echo", "fail", "boom", "changed", "t1",
            "t2", "t3");
        assertThat(methodsReceived().stream().filter("tools/list"::equals)).hasSize(4);
        assertThat(tools.get(0).inputSchema().path("type").asText()).isEqualTo("object");
    }

    @Test
    void a_cursor_seen_before_ends_listing() {
        server.loopingCursor = true;
        assertThat(client(Duration.ofSeconds(5)).listTools()).hasSize(8);
    }

    @Test
    void call_tool_results_and_errors() {
        McpClient c = client(Duration.ofSeconds(5));
        CallToolResult ok = c.callTool("echo", text("hi"), CancelToken.none());
        assertThat(ok.isError()).isFalse();
        assertThat(ok.content().get(0).path("text").asText()).isEqualTo("hi");
        assertThat(c.callTool("fail", text(""), CancelToken.none()).isError()).isTrue();
        assertThatThrownBy(() -> c.callTool("boom", text(""), CancelToken.none())).isInstanceOf(McpException.class)
            .hasMessage("bad arguments").extracting(e -> ((McpException)e).code()).isEqualTo(JsonRpc.INVALID_PARAMS);
    }

    @Test
    void list_changed_notification_reaches_the_handler() throws Exception {
        McpClient c = client(Duration.ofSeconds(5));
        CompletableFuture<JsonNode> seen = new CompletableFuture<>();
        c.onNotification(McpServerManager.TOOLS_CHANGED, seen::complete);
        c.callTool("changed", text(""), CancelToken.none());
        assertThat(seen.get(5, TimeUnit.SECONDS).isObject()).isTrue();
    }

    @Test
    void timeout_fails_the_call_and_tells_the_server() {
        McpClient c = client(Duration.ofMillis(200));
        server.silent = Set.of("tools/call");
        assertThatThrownBy(() -> c.callTool("echo", text("x"), CancelToken.none())).isInstanceOf(McpException.class)
            .hasMessageContaining("timed out");
        awaitMethod("notifications/cancelled");
        JsonNode cancelled = server.received.stream()
            .filter(m -> "notifications/cancelled".equals(m.path("method").asText())).findFirst().orElseThrow();
        assertThat(cancelled.path("params").path("reason").asText()).isEqualTo("timeout");
    }

    @Test
    void cancel_token_aborts_a_call() {
        McpClient c = client(Duration.ofSeconds(10));
        server.silent = Set.of("tools/call");
        CancelTokenSource cancel = new CancelTokenSource();
        Thread.ofVirtual().start(() -> {
            awaitMethod("tools/call");
            cancel.cancel();
        });
        assertThatThrownBy(() -> c.callTool("echo", text("x"), cancel)).isInstanceOf(McpException.class)
            .hasMessageContaining("cancelled");
        awaitMethod("notifications/cancelled");
    }

    @Test
    void an_already_cancelled_token_never_sends_the_request() {
        McpClient c = client(Duration.ofSeconds(5));
        CancelTokenSource cancel = new CancelTokenSource();
        cancel.cancel();
        assertThatThrownBy(() -> c.callTool("echo", text("x"), cancel)).isInstanceOf(McpException.class)
            .hasMessageContaining("cancelled");
        c.listTools();
        assertThat(methodsReceived()).doesNotContain("tools/call", "notifications/cancelled");
    }

    @Test
    void close_fails_pending_calls_and_refuses_new_ones() throws Exception {
        McpClient c = client(Duration.ofSeconds(10));
        server.silent = Set.of("tools/call");
        List<String> closedWith = new CopyOnWriteArrayList<>();
        c.onClosed(closedWith::add);
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                c.callTool("echo", text("x"), CancelToken.none());
                failure.complete(null);
            } catch (McpException e) {
                failure.complete(e);
            }
        });
        awaitMethod("tools/call");
        c.close();
        assertThat(failure.get(5, TimeUnit.SECONDS)).isInstanceOf(McpException.class)
            .hasMessageContaining("connection closed");
        assertThat(c.isClosed()).isTrue();
        assertThat(closedWith).containsExactly("client closed");
        assertThatThrownBy(c::listTools).isInstanceOf(McpException.class).hasMessageContaining("refused");
    }

    @Test
    void server_going_away_fails_pending_calls() throws Exception {
        McpClient c = client(Duration.ofSeconds(10));
        server.silent = Set.of("tools/call");
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                c.callTool("echo", text("x"), CancelToken.none());
            } catch (McpException e) {
                failure.complete(e);
            }
        });
        awaitMethod("tools/call");
        transport.kill();
        assertThat(failure.get(5, TimeUnit.SECONDS)).hasMessageContaining("killed");
    }

    @Test
    void server_requests_get_answers() {
        List<JsonNode> sent = new CopyOnWriteArrayList<>();
        List<Consumer<JsonNode>> receivers = new CopyOnWriteArrayList<>();
        McpTransport capture = new McpTransport() {
            @Override
            public void send(JsonNode msg) {
                sent.add(msg);
            }

            @Override
            public void onReceive(Consumer<JsonNode> c) {
                receivers.add(c);
            }

            @Override
            public void onClosed(Consumer<String> c) {
                // never closes by itself
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        new McpClient(capture, Duration.ofSeconds(1));
        receivers.get(0).accept(JsonRpc.encode(new JsonRpc.Request(IntNode.valueOf(9), "ping", Optional.empty())));
        receivers.get(0).accept(
            JsonRpc.encode(new JsonRpc.Request(IntNode.valueOf(10), "sampling/createMessage", Optional.empty())));
        assertThat(JsonRpc.decode(sent.get(0)))
            .contains(JsonRpc.Response.success(IntNode.valueOf(9), Json.mapper().createObjectNode()));
        assertThat(sent.get(1).path("error").path("code").asInt()).isEqualTo(JsonRpc.METHOD_NOT_FOUND);
    }

    private void awaitMethod(String method) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!methodsReceived().contains(method)) {
            assertThat(System.nanoTime()).as("waiting for " + method).isLessThan(deadline);
            Thread.onSpinWait();
        }
    }
}
