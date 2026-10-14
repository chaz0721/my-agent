/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;

import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonRpcTest {

    private static JsonNode json(String s) throws Exception {
        return Json.mapper().readTree(s);
    }

    @Test
    void messages_round_trip() {
        JsonRpc.Message[] all =
            {new JsonRpc.Request(IntNode.valueOf(7), "tools/list", Optional.of(Json.mapper().createObjectNode())),
                new JsonRpc.Request(TextNode.valueOf("a"), "ping", Optional.empty()),
                new JsonRpc.Notification("notifications/initialized", Optional.empty()),
                JsonRpc.Response.success(IntNode.valueOf(7), Json.mapper().createObjectNode().put("x", 1)),
                JsonRpc.Response.failure(TextNode.valueOf("a"), JsonRpc.METHOD_NOT_FOUND, "nope")};
        for (JsonRpc.Message m : all) {
            JsonNode encoded = JsonRpc.encode(m);
            assertThat(encoded.get("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(JsonRpc.decode(encoded)).contains(m);
        }
    }

    @Test
    void error_data_survives_and_keys_match_numbers_and_strings() throws Exception {
        JsonRpc.Message m = JsonRpc
            .decode(json(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-1,\"message\":" + "\"m\",\"data\":{\"k\":true}}}"))
            .orElseThrow();
        JsonRpc.Response r = (JsonRpc.Response)m;
        assertThat(r.error().orElseThrow().data().orElseThrow().get("k").asBoolean()).isTrue();
        assertThat(JsonRpc.key(IntNode.valueOf(3))).isEqualTo(JsonRpc.key(TextNode.valueOf("3")));
    }

    @Test
    void invalid_messages_are_rejected() throws Exception {
        assertThat(JsonRpc.decode(json("{\"id\":1,\"result\":{}}"))).isEmpty();
        assertThat(JsonRpc.decode(json("{\"jsonrpc\":\"1.0\",\"id\":1,\"result\":{}}"))).isEmpty();
        assertThat(JsonRpc.decode(json("{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"x\"}"))).isEmpty();
        assertThat(JsonRpc.decode(json("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"error\":{\"code\":1}}")))
            .isEmpty();
        assertThat(JsonRpc.decode(json("{\"jsonrpc\":\"2.0\",\"id\":null,\"result\":{}}"))).isEmpty();
        assertThat(JsonRpc.decode(json("[1,2]"))).isEmpty();
        assertThatThrownBy(() -> new JsonRpc.Response(IntNode.valueOf(1), Optional.empty(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ids_are_unique_and_increasing() {
        JsonRpc rpc = new JsonRpc();
        long a = rpc.nextId();
        long b = rpc.nextId();
        assertThat(b).isGreaterThan(a);
    }
}
