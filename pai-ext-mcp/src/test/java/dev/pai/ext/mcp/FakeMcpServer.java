/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.Json;

/**
 * A scriptable MCP server for tests. Tools: {@code echo} (returns {@code text}), {@code fail} (isError), {@code boom}
 * (JSON-RPC error), {@code changed} (sends {@code tools/list_changed} first) and any extra names in {@link #tools}.
 */
final class FakeMcpServer {

    final List<String> tools = new CopyOnWriteArrayList<>(List.of("echo", "fail", "boom", "changed"));
    final List<JsonNode> received = new CopyOnWriteArrayList<>();
    volatile int pageSize = Integer.MAX_VALUE;
    volatile boolean loopingCursor;
    volatile boolean resources;
    volatile boolean toolsCapability = true;
    volatile Set<String> silent = Set.of();
    volatile CountDownLatch initGate = new CountDownLatch(0);

    private final Consumer<JsonNode> out;

    FakeMcpServer(Consumer<JsonNode> out) {
        this.out = out;
    }

    /** Sends {@code notifications/tools/list_changed}. */
    void toolsChanged() {
        out.accept(
            Json.mapper().createObjectNode().put("jsonrpc", "2.0").put("method", McpServerManager.TOOLS_CHANGED));
    }

    /** Handles one client message; answers go to {@code out}. */
    void receive(JsonNode msg) {
        received.add(msg);
        if (!msg.has("id") || !msg.has("method")) {
            return;
        }
        String method = msg.get("method").asText();
        if (silent.contains(method)) {
            return;
        }
        JsonNode params = msg.path("params");
        switch (method) {
            case "initialize" -> {
                await();
                reply(msg, initialize());
            }
            case "tools/list" -> reply(msg, page(params.path("cursor").asText("0")));
            case "tools/call" -> call(msg, params);
            case "resources/list" -> {
                ObjectNode r = Json.mapper().createObjectNode();
                r.putArray("resources").addObject().put("uri", "mem://a").put("name", "a").put("mimeType",
                    "text/plain");
                reply(msg, r);
            }
            case "resources/read" -> {
                ObjectNode r = Json.mapper().createObjectNode();
                r.putArray("contents").addObject().put("uri", params.path("uri").asText()).put("text", "A");
                reply(msg, r);
            }
            default -> error(msg, JsonRpc.METHOD_NOT_FOUND, "no " + method);
        }
    }

    private void await() {
        try {
            initGate.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ObjectNode initialize() {
        ObjectNode r = Json.mapper().createObjectNode().put("protocolVersion", McpClient.PROTOCOL_VERSION);
        ObjectNode caps = r.putObject("capabilities");
        if (toolsCapability) {
            caps.putObject("tools").put("listChanged", true);
        }
        if (resources) {
            caps.putObject("resources");
        }
        r.putObject("serverInfo").put("name", "fake").put("version", "1.0");
        return r;
    }

    private ObjectNode page(String cursor) {
        int from = Integer.parseInt(cursor);
        List<String> all = List.copyOf(tools);
        int to = (int)Math.min(all.size(), (long)from + pageSize);
        ObjectNode r = Json.mapper().createObjectNode();
        ArrayNode arr = r.putArray("tools");
        for (String name : all.subList(from, to)) {
            ObjectNode t = arr.addObject().put("name", name).put("description", "tool " + name);
            ObjectNode schema = t.putObject("inputSchema").put("type", "object");
            schema.putObject("properties").putObject("text").put("type", "string");
        }
        if (loopingCursor) {
            r.put("nextCursor", "0");
        } else if (to < all.size()) {
            r.put("nextCursor", String.valueOf(to));
        }
        return r;
    }

    private void call(JsonNode msg, JsonNode params) {
        String name = params.path("name").asText();
        ObjectNode r = Json.mapper().createObjectNode();
        switch (name) {
            case "echo" -> r.putArray("content").addObject().put("type", "text").put("text",
                params.path("arguments").path("text").asText());
            case "fail" -> {
                r.putArray("content").addObject().put("type", "text").put("text", "failed");
                r.put("isError", true);
            }
            case "boom" -> {
                error(msg, JsonRpc.INVALID_PARAMS, "bad arguments");
                return;
            }
            case "changed" -> {
                toolsChanged();
                r.putArray("content").addObject().put("type", "text").put("text", "ok");
            }
            default -> r.putArray("content").addObject().put("type", "text").put("text", name);
        }
        reply(msg, r);
    }

    private void reply(JsonNode msg, JsonNode result) {
        out.accept(JsonRpc.encode(JsonRpc.Response.success(msg.get("id"), result)));
    }

    private void error(JsonNode msg, int code, String text) {
        out.accept(JsonRpc.encode(JsonRpc.Response.failure(msg.get("id"), code, text)));
    }
}
