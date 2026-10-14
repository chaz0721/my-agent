/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.CancelToken;

/**
 * An MCP client over one {@link McpTransport}: request/response matching, timeouts, cancellation, notification dispatch
 * and the typed MCP calls. Calls block the calling thread; several may be in flight at once. After {@link #close()} or
 * once the transport closes, pending calls fail with {@link McpException} and new ones are refused. Notification
 * handlers run on the transport thread and must not block.
 */
public final class McpClient implements AutoCloseable {

    /** Protocol version requested in {@code initialize}. */
    public static final String PROTOCOL_VERSION = "2025-06-18";
    /** Upper bound of pages followed by a list call. */
    public static final int MAX_PAGES = 100;
    /** Upper bound of items collected by a list call. */
    public static final int MAX_ITEMS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final McpTransport transport;
    private final Duration timeout;
    private final JsonRpc rpc = new JsonRpc();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<JsonNode>>> handlers = new ConcurrentHashMap<>();
    private final List<Consumer<String>> closeListeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private volatile String closeReason = "client closed";

    /**
     * Creates a client and starts listening on the transport.
     *
     * @param t the transport
     * @param requestTimeout how long one request may take
     */
    public McpClient(McpTransport t, Duration requestTimeout) {
        this.transport = t;
        this.timeout = requestTimeout;
        t.onReceive(this::receive);
        t.onClosed(this::closedBy);
    }

    /**
     * Performs the {@code initialize} handshake and sends {@code notifications/initialized}.
     *
     * @param info how the client introduces itself
     * @return what the server answered
     */
    public InitializeResult initialize(ClientInfo info) {
        ObjectNode params = NODES.objectNode().put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", info.name()).put("version", info.version());
        JsonNode r = request("initialize", params, CancelToken.none());
        notify("notifications/initialized", Optional.empty());
        JsonNode server = r.path("serverInfo");
        String version = r.path("protocolVersion").asText("");
        if (!PROTOCOL_VERSION.equals(version)) {
            log.debug("server {} speaks protocol {}", server.path("name").asText(), version);
        }
        Optional<String> instructions =
            r.hasNonNull("instructions") ? Optional.of(r.get("instructions").asText()) : Optional.empty();
        return new InitializeResult(version, server.path("name").asText(""), server.path("version").asText(""),
            r.path("capabilities"), instructions);
    }

    /**
     * Lists the server's tools, following cursors up to {@link #MAX_PAGES} pages and {@link #MAX_ITEMS} tools.
     *
     * @return the tools
     */
    public List<McpToolDescriptor> listTools() {
        List<McpToolDescriptor> out = new ArrayList<>();
        for (JsonNode t : list("tools/list", "tools")) {
            out.add(new McpToolDescriptor(t.path("name").asText(), t.path("description").asText(""),
                t.path("inputSchema")));
        }
        return out;
    }

    /**
     * Calls a tool.
     *
     * @param name the server's tool name
     * @param args arguments object
     * @param c cancels the call (the server is told with {@code notifications/cancelled})
     * @return the result; a tool-level failure is {@link CallToolResult#isError()}, not an exception
     */
    public CallToolResult callTool(String name, JsonNode args, CancelToken c) {
        ObjectNode params = NODES.objectNode().put("name", name);
        params.set("arguments", args);
        JsonNode r = request("tools/call", params, c);
        List<JsonNode> content = new ArrayList<>();
        r.path("content").forEach(content::add);
        return new CallToolResult(content, r.path("isError").asBoolean(false),
            Optional.ofNullable(r.get("structuredContent")));
    }

    /**
     * Lists the server's resources, following cursors like {@link #listTools()}.
     *
     * @return the resources
     */
    public List<McpResource> listResources() {
        List<McpResource> out = new ArrayList<>();
        for (JsonNode r : list("resources/list", "resources")) {
            out.add(new McpResource(r.path("uri").asText(), r.path("name").asText(""), r.path("description").asText(""),
                r.path("mimeType").asText("")));
        }
        return out;
    }

    /**
     * Reads a resource.
     *
     * @param uri resource URI
     * @return its contents
     */
    public ReadResourceResult readResource(String uri) {
        JsonNode r = request("resources/read", NODES.objectNode().put("uri", uri), CancelToken.none());
        List<JsonNode> contents = new ArrayList<>();
        r.path("contents").forEach(contents::add);
        return new ReadResourceResult(contents);
    }

    /**
     * Registers a handler for a server notification such as {@code notifications/tools/list_changed}.
     *
     * @param method notification method
     * @param handler receives the params (an empty object when absent), on the transport thread
     */
    public void onNotification(String method, Consumer<JsonNode> handler) {
        handlers.computeIfAbsent(method, m -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Registers a listener called once when the client closes, with the reason.
     *
     * @param listener the listener
     */
    public void onClosed(Consumer<String> listener) {
        closeListeners.add(listener);
    }

    /**
     * Returns whether the client is closed.
     *
     * @return true after {@link #close()} or once the transport closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Fails every pending request, refuses new ones and closes the transport.
     */
    @Override
    public void close() {
        shutDown("client closed");
        transport.close();
    }

    /**
     * Sends a request and waits for its result.
     *
     * @param method method name
     * @param params parameters
     * @param c cancellation
     * @return the result member
     * @throws McpException on an error response, timeout, cancellation or closed connection
     */
    JsonNode request(String method, JsonNode params, CancelToken c) {
        long id = rpc.nextId();
        String key = String.valueOf(id);
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(key, f);
        if (closed) {
            pending.remove(key);
            throw new McpException(method + " refused: " + closeReason);
        }
        AtomicBoolean sent = new AtomicBoolean();
        c.onCancel(() -> {
            if (f.completeExceptionally(new McpException(method + " cancelled")) && sent.get()) {
                cancelRemote(id, "cancelled");
            }
        });
        try {
            if (!f.isDone()) {
                sent.set(true);
                transport.send(JsonRpc.encode(new JsonRpc.Request(LongNode.valueOf(id), method, Optional.of(params))));
            }
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            throw new McpException(method + " failed: " + e.getMessage(), e);
        } catch (TimeoutException e) {
            cancelRemote(id, "timeout");
            throw new McpException(method + " timed out after " + timeout.toMillis() + " ms", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof McpException m ? m : new McpException(method + " failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(method + " interrupted", e);
        } finally {
            pending.remove(key);
        }
    }

    private List<JsonNode> list(String method, String member) {
        List<JsonNode> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Optional<String> cursor = Optional.empty();
        for (int page = 0; page < MAX_PAGES; page++) {
            ObjectNode params = NODES.objectNode();
            cursor.ifPresent(cur -> params.put("cursor", cur));
            JsonNode r = request(method, params, CancelToken.none());
            for (JsonNode item : r.path(member)) {
                if (out.size() >= MAX_ITEMS) {
                    log.warn("{} stopped at {} items", method, MAX_ITEMS);
                    return out;
                }
                out.add(item);
            }
            String next = r.path("nextCursor").asText("");
            if (next.isEmpty() || !seen.add(next)) {
                return out;
            }
            cursor = Optional.of(next);
        }
        log.warn("{} stopped after {} pages", method, MAX_PAGES);
        return out;
    }

    private void notify(String method, Optional<JsonNode> params) {
        try {
            transport.send(JsonRpc.encode(new JsonRpc.Notification(method, params)));
        } catch (IOException e) {
            log.debug("cannot send {}", method, e);
        }
    }

    private void cancelRemote(long id, String reason) {
        if (!closed) {
            notify("notifications/cancelled",
                Optional.of(NODES.objectNode().put("requestId", id).put("reason", reason)));
        }
    }

    private void receive(JsonNode raw) {
        Optional<JsonRpc.Message> decoded = JsonRpc.decode(raw);
        if (decoded.isEmpty()) {
            log.debug("ignoring invalid message");
            return;
        }
        switch (decoded.get()) {
            case JsonRpc.Response r -> complete(r);
            case JsonRpc.Notification n -> dispatch(n);
            case JsonRpc.Request r -> answer(r);
        }
    }

    private void complete(JsonRpc.Response r) {
        CompletableFuture<JsonNode> f = pending.get(JsonRpc.key(r.id()));
        if (f == null) {
            log.debug("response to unknown or finished request {}", r.id());
            return;
        }
        if (r.error().isPresent()) {
            JsonRpc.ErrorObject e = r.error().get();
            f.completeExceptionally(new McpException(e.code(), e.message()));
        } else {
            f.complete(r.result().orElseThrow());
        }
    }

    private void dispatch(JsonRpc.Notification n) {
        JsonNode params = n.params().orElseGet(NODES::objectNode);
        for (Consumer<JsonNode> h : handlers.getOrDefault(n.method(), List.of())) {
            try {
                h.accept(params);
            } catch (RuntimeException e) {
                log.warn("handler of {} failed", n.method(), e);
            }
        }
    }

    /** Answers server-to-client requests: {@code ping}; everything else is not supported by this client. */
    private void answer(JsonRpc.Request r) {
        JsonRpc.Response reply = "ping".equals(r.method()) ? JsonRpc.Response.success(r.id(), NODES.objectNode())
            : JsonRpc.Response.failure(r.id(), JsonRpc.METHOD_NOT_FOUND, "unsupported: " + r.method());
        try {
            transport.send(JsonRpc.encode(reply));
        } catch (IOException e) {
            log.debug("cannot answer {}", r.method(), e);
        }
    }

    private void closedBy(String reason) {
        shutDown(reason);
    }

    private void shutDown(String reason) {
        synchronized (this) {
            if (closed) {
                return;
            }
            closeReason = reason;
            closed = true;
        }
        McpException e = new McpException("connection closed: " + reason);
        pending.values().forEach(f -> f.completeExceptionally(e));
        for (Consumer<String> l : closeListeners) {
            try {
                l.accept(reason);
            } catch (RuntimeException ex) {
                log.warn("close listener failed", ex);
            }
        }
    }
}
