/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 as MCP uses it: the three message kinds, their encoding and decoding, and request id generation. No I/O.
 */
public final class JsonRpc {

    /** Value of the {@code jsonrpc} member. */
    public static final String VERSION = "2.0";
    /** Error code: the method does not exist. */
    public static final int METHOD_NOT_FOUND = -32601;
    /** Error code: invalid method parameters. */
    public static final int INVALID_PARAMS = -32602;
    /** Error code: internal error. */
    public static final int INTERNAL_ERROR = -32603;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final AtomicLong ids = new AtomicLong();

    /**
     * Returns a fresh request id, unique for this instance.
     *
     * @return the id
     */
    public long nextId() {
        return ids.incrementAndGet();
    }

    /**
     * A JSON-RPC message.
     */
    public sealed interface Message permits Request, Notification, Response {}

    /**
     * A call that expects a response.
     *
     * @param id request id (number or string)
     * @param method method name
     * @param params parameters
     */
    public record Request(JsonNode id, String method, Optional<JsonNode> params) implements Message {
        /**
         * Validates fields.
         */
        public Request {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(params, "params");
        }
    }

    /**
     * A one-way message.
     *
     * @param method method name
     * @param params parameters
     */
    public record Notification(String method, Optional<JsonNode> params) implements Message {
        /**
         * Validates fields.
         */
        public Notification {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(params, "params");
        }
    }

    /**
     * The answer to a request: a result or an error.
     *
     * @param id id of the request
     * @param result the result when successful
     * @param error the error when failed
     */
    public record Response(JsonNode id, Optional<JsonNode> result, Optional<ErrorObject> error) implements Message {
        /**
         * Validates that exactly one of result and error is present.
         */
        public Response {
            Objects.requireNonNull(id, "id");
            if (result.isPresent() == error.isPresent()) {
                throw new IllegalArgumentException("response needs exactly one of result and error");
            }
        }

        /**
         * Creates a successful response.
         *
         * @param id request id
         * @param result the result
         * @return the response
         */
        public static Response success(JsonNode id, JsonNode result) {
            return new Response(id, Optional.of(result), Optional.empty());
        }

        /**
         * Creates an error response.
         *
         * @param id request id
         * @param code error code
         * @param message error message
         * @return the response
         */
        public static Response failure(JsonNode id, int code, String message) {
            return new Response(id, Optional.empty(), Optional.of(new ErrorObject(code, message, Optional.empty())));
        }
    }

    /**
     * The error member of a response.
     *
     * @param code error code
     * @param message short description
     * @param data additional information
     */
    public record ErrorObject(int code, String message, Optional<JsonNode> data) {
    }

    /**
     * Returns the key under which a request id is matched with its response.
     *
     * @param id a request id
     * @return the key; numbers and strings with the same text map to the same key
     */
    public static String key(JsonNode id) {
        return id.asText();
    }

    /**
     * Encodes a message.
     *
     * @param m the message
     * @return its JSON object
     */
    public static ObjectNode encode(Message m) {
        ObjectNode o = NODES.objectNode().put("jsonrpc", VERSION);
        switch (m) {
            case Request r -> {
                o.set("id", r.id());
                o.put("method", r.method());
                r.params().ifPresent(p -> o.set("params", p));
            }
            case Notification n -> {
                o.put("method", n.method());
                n.params().ifPresent(p -> o.set("params", p));
            }
            case Response r -> {
                o.set("id", r.id());
                r.result().ifPresent(v -> o.set("result", v));
                r.error().ifPresent(e -> {
                    ObjectNode err = o.putObject("error").put("code", e.code()).put("message", e.message());
                    e.data().ifPresent(d -> err.set("data", d));
                });
            }
        }
        return o;
    }

    /**
     * Decodes a message.
     *
     * @param n a JSON value received from a peer
     * @return the message, or empty when the value is not a valid JSON-RPC 2.0 message
     */
    public static Optional<Message> decode(JsonNode n) {
        if (!n.isObject() || !VERSION.equals(n.path("jsonrpc").asText())) {
            return Optional.empty();
        }
        JsonNode id = n.get("id");
        boolean validId = id != null && (id.isTextual() || id.isIntegralNumber());
        Optional<JsonNode> params = Optional.ofNullable(n.get("params"));
        if (n.path("method").isTextual()) {
            String method = n.get("method").asText();
            if (id == null) {
                return Optional.of(new Notification(method, params));
            }
            return validId ? Optional.of(new Request(id, method, params)) : Optional.empty();
        }
        if (!validId) {
            return Optional.empty();
        }
        JsonNode result = n.get("result");
        JsonNode error = n.get("error");
        if (result != null && error == null) {
            return Optional.of(Response.success(id, result));
        }
        if (error != null && result == null && error.path("code").isIntegralNumber()) {
            ErrorObject e = new ErrorObject(error.get("code").asInt(), error.path("message").asText(""),
                Optional.ofNullable(error.get("data")));
            return Optional.of(new Response(id, Optional.empty(), Optional.of(e)));
        }
        return Optional.empty();
    }
}
