/* pai-next — Apache-2.0 */
package dev.pai.model.codec.anthropic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.AssistantMessage;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.Fixtures;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.StreamEvent;
import dev.pai.model.SystemMessage;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;
import dev.pai.model.codec.RequestHistory;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicMessagesCodecTest {

    private final AnthropicMessagesCodec codec = new AnthropicMessagesCodec("anthropic");

    private List<StreamEvent> replay(String name) {
        AnthropicMessagesCodec.StreamDecoder d = codec.newDecoder();
        return Fixtures.replay("/fixtures/anthropic/" + name, d::feed, d::finish);
    }

    @Test
    void decodes_text_with_cache_usage() {
        AssistantMessage m = Fixtures.terminal(replay("text.sse"));
        assertThat(m.content()).containsExactly(new ContentBlock.Text("Hello from the fixture."));
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.STOP);
        assertThat(m.usage()).isEqualTo(new Usage(25L, 7L, 10L, 0L));
        assertThat(m.modelId()).isEqualTo("claude-sonnet-4-5");
    }

    @Test
    void assembles_tool_use_from_input_json_deltas() {
        List<StreamEvent> events = replay("tool-use.sse");
        assertThat(events).filteredOn(StreamEvent.ToolCallStart.class::isInstance)
            .containsExactly(new StreamEvent.ToolCallStart(1, "toolu_01FixtureRead", "read"));
        StreamEvent.ToolCallEnd end = events.stream().filter(StreamEvent.ToolCallEnd.class::isInstance)
            .map(StreamEvent.ToolCallEnd.class::cast).findFirst().orElseThrow();
        assertThat(end.index()).isEqualTo(1);
        assertThat(end.toolCall().arguments().path("path").asText()).isEqualTo("README.md");
        assertThat(end.toolCall().arguments().path("limit").asInt()).isEqualTo(20);
        AssistantMessage m = Fixtures.terminal(events);
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.TOOL_USE);
        assertThat(m.text()).isEqualTo("Let me read it.");
        assertThat(m.usage().output()).isEqualTo(58L);
    }

    @Test
    void keeps_thinking_signature() {
        AssistantMessage m = Fixtures.terminal(replay("thinking.sse"));
        ContentBlock.Thinking t = (ContentBlock.Thinking)m.content().get(0);
        assertThat(t.text()).isEqualTo("17 * 23 = 17 * 20 + 17 * 3 = 340 + 51 = 391.");
        assertThat(t.signature()).contains("EqQBCgIYAhIM1gbcDa9GJwZA2b3hGgxBdjrkzLoky3dl1pkiMOYds");
        assertThat(m.text()).isEqualTo("391");
    }

    @Test
    void error_event_terminates_with_partial() {
        List<StreamEvent> events = replay("error.sse");
        StreamEvent.Error err = (StreamEvent.Error)events.get(events.size() - 1);
        assertThat(err.message()).isEqualTo("Overloaded");
        assertThat(err.partial().orElseThrow().text()).isEqualTo("Partial");
    }

    @Test
    void stream_without_message_stop_is_an_error() {
        AnthropicMessagesCodec.StreamDecoder d = codec.newDecoder();
        d.feed("{\"type\":\"message_start\",\"message\":{\"model\":\"m\",\"usage\":{\"input_tokens\":1}}}");
        assertThat(d.finish()).last().isInstanceOf(StreamEvent.Error.class);
    }

    @Test
    void request_body_matches_golden() {
        ObjectNode body = codec.toRequestBody(RequestHistory.request("anthropic", "EqSig==",
            Set.of(ModelRef.CapabilityEnum.TOOLS, ModelRef.CapabilityEnum.IMAGE_INPUT)));
        assertThat(body).isEqualTo(Fixtures.json("/golden/anthropic-request.json"));
    }

    @Test
    void thinking_option_sets_budget_and_drops_temperature() {
        ModelRef model = new ModelRef("anthropic", "m", java.net.URI.create("https://x.invalid"), 200000, 8192,
            Set.of(ModelRef.CapabilityEnum.THINKING));
        ChatOptions opts =
            new ChatOptions(OptionalInt.empty(), OptionalDouble.of(0.5d), ChatOptions.ThinkingLevelEnum.LOW, Map.of());
        ChatRequest req = new ChatRequest(model, Optional.empty(),
            List.of(new SystemMessage("extra rule"), UserMessage.text("hi")), List.of(), opts);
        JsonNode body = codec.toRequestBody(req);
        assertThat(body.path("thinking").path("budget_tokens").asInt()).isEqualTo(2048);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(8192);
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.path("system").asText()).isEqualTo("extra rule");
    }

    @Test
    void unsigned_thinking_is_sent_as_text_and_redacted_round_trips() {
        ContentBlock.Thinking unsigned = new ContentBlock.Thinking("loose thought", Optional.empty());
        ContentBlock.Thinking redacted = new ContentBlock.Thinking("", Optional.of("redacted:OPAQUE"));
        AssistantMessage a = new AssistantMessage(List.of(redacted, unsigned), "anthropic", "m", Usage.ZERO,
            StopReasonEnum.STOP, Optional.empty(), java.time.Instant.EPOCH);
        ChatRequest base = RequestHistory.request("anthropic", "s", Set.of());
        ChatRequest req = new ChatRequest(base.model(), Optional.empty(), List.of(UserMessage.text("q"), a), List.of(),
            ChatOptions.defaults());
        JsonNode content = codec.toRequestBody(req).path("messages").path(1).path("content");
        assertThat(content.path(0).path("type").asText()).isEqualTo("redacted_thinking");
        assertThat(content.path(0).path("data").asText()).isEqualTo("OPAQUE");
        assertThat(content.path(1).path("type").asText()).isEqualTo("text");
    }
}
