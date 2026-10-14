/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class MessageJsonRoundTripTest {

    private static final Instant T = Instant.parse("2026-10-15T01:02:03.456Z");

    @Test
    void every_message_and_block_kind_round_trips() throws JsonProcessingException {
        JsonNode args = Json.mapper().readTree("{\"path\":\"a.txt\",\"nested\":{\"list\":[1,2]}}");
        List<Message> messages = List.of(new SystemMessage("be brief"),
            new UserMessage(List.of(new ContentBlock.Text("hi"), new ContentBlock.Image("AAAA", "image/png")), T),
            new AssistantMessage(
                List.of(new ContentBlock.Thinking("hmm", Optional.of("sig")),
                    new ContentBlock.Thinking("plain", Optional.empty()), new ContentBlock.Text("ok"),
                    new ContentBlock.ToolCall("call_1", "read", args)),
                "anthropic", "claude", new Usage(1L, 2L, 3L, 4L), StopReasonEnum.TOOL_USE, Optional.empty(), T),
            new AssistantMessage(List.of(), "p", "m", Usage.ZERO, StopReasonEnum.ERROR, Optional.of("boom"), T),
            new ToolResultMessage("call_1", "read", List.of(new ContentBlock.Text("1\tx")), true, T));
        for (Message m : messages) {
            String json = Json.mapper().writeValueAsString(m);
            assertThat(Json.mapper().readValue(json, Message.class)).as(json).isEqualTo(m);
        }
    }

    @Test
    void uses_snake_case_type_discriminators_and_iso_timestamps() throws JsonProcessingException {
        ToolResultMessage m = new ToolResultMessage("c", "bash", List.of(new ContentBlock.Text("x")), false, T);
        JsonNode node = Json.mapper().valueToTree(m);
        assertThat(node.path("type").asText()).isEqualTo("tool_result");
        assertThat(node.path("timestamp").asText()).isEqualTo("2026-10-15T01:02:03.456Z");
        assertThat(node.path("content").path(0).path("type").asText()).isEqualTo("text");
        JsonNode call =
            Json.mapper().valueToTree(new ContentBlock.ToolCall("i", "n", Json.mapper().createObjectNode()));
        assertThat(call.path("type").asText()).isEqualTo("tool_call");
    }

    @Test
    void empty_optionals_are_omitted_and_restored() throws JsonProcessingException {
        ContentBlock.Thinking t = new ContentBlock.Thinking("x", Optional.empty());
        String json = Json.mapper().writeValueAsString(t);
        assertThat(json).doesNotContain("signature");
        assertThat(Json.mapper().readValue(json, ContentBlock.class)).isEqualTo(t);
    }

    @Test
    void usage_arithmetic() {
        Usage u = new Usage(1L, 2L, 3L, 4L).plus(new Usage(10L, 20L, 30L, 40L));
        assertThat(u).isEqualTo(new Usage(11L, 22L, 33L, 44L));
        assertThat(u.total()).isEqualTo(110L);
        assertThat(Usage.ZERO.total()).isZero();
    }

    @Test
    void assistant_helpers_concatenate_text_and_list_tool_calls() {
        ContentBlock.ToolCall call = new ContentBlock.ToolCall("c", "read", Json.mapper().createObjectNode());
        AssistantMessage a = new AssistantMessage(List.of(new ContentBlock.Text("a"), call, new ContentBlock.Text("b")),
            "p", "m", Usage.ZERO, StopReasonEnum.TOOL_USE, Optional.empty(), T);
        assertThat(a.text()).isEqualTo("ab");
        assertThat(a.toolCalls()).containsExactly(call);
    }
}
