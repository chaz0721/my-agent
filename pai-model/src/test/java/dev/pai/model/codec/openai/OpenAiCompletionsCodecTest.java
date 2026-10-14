/* pai-next — Apache-2.0 */
package dev.pai.model.codec.openai;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.Fixtures;
import dev.pai.model.Json;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.StreamEvent;
import dev.pai.model.Usage;
import dev.pai.model.codec.RequestHistory;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompletionsCodecTest {

    private final OpenAiCompletionsCodec codec = new OpenAiCompletionsCodec("openai-compat", true);

    private List<StreamEvent> replay(String name) {
        OpenAiCompletionsCodec.StreamDecoder d = codec.newDecoder();
        return Fixtures.replay("/fixtures/openai-compat/" + name, d::feed, d::finish);
    }

    @Test
    void decodes_plain_text_stream() {
        List<StreamEvent> events = replay("text.sse");
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Start.class);
        assertThat(events).filteredOn(StreamEvent.TextDelta.class::isInstance)
            .allSatisfy(e -> assertThat(((StreamEvent.TextDelta)e).index()).isZero()).hasSize(5);
        AssistantMessage m = Fixtures.terminal(events);
        assertThat(events.get(events.size() - 1)).isInstanceOf(StreamEvent.Done.class);
        assertThat(m.content()).containsExactly(new ContentBlock.Text("Hello from the fixture."));
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.STOP);
        assertThat(m.modelId()).isEqualTo("deepseek-flash");
        assertThat(m.usage()).isEqualTo(new Usage(13L, 5L, 0L, 0L));
    }

    @Test
    void merges_parallel_tool_call_fragments_by_index() {
        List<StreamEvent> events = replay("tool-calls-parallel.sse");
        assertThat(events).filteredOn(StreamEvent.ToolCallStart.class::isInstance)
            .extracting(e -> ((StreamEvent.ToolCallStart)e).index()).containsExactly(0, 1);
        assertThat(events).filteredOn(StreamEvent.ToolCallDelta.class::isInstance).hasSizeGreaterThan(4);
        AssistantMessage m = Fixtures.terminal(events);
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.TOOL_USE);
        assertThat(m.toolCalls()).extracting(ContentBlock.ToolCall::id)
            .containsExactly("call_00_mqDODxcUCnAXVmmJjXfe7897", "call_01_Q79IOuw6c8vGUPCgyBlV5855");
        assertThat(m.toolCalls()).extracting(c -> c.arguments().path("path").asText()).containsExactly("a.txt",
            "b.txt");
        assertThat(m.errorMessage()).isEmpty();
        List<StreamEvent> ends = events.stream().filter(StreamEvent.ToolCallEnd.class::isInstance).toList();
        assertThat(ends).hasSize(2);
    }

    @Test
    void reasoning_content_becomes_thinking_with_field_signature() {
        AssistantMessage m = Fixtures.terminal(replay("reasoning.sse"));
        assertThat(m.content()).hasSize(2);
        ContentBlock.Thinking thinking = (ContentBlock.Thinking)m.content().get(0);
        assertThat(thinking.text()).contains("17*23=391");
        assertThat(thinking.signature()).contains("reasoning_content");
        assertThat(m.text()).isEqualTo("391");
    }

    @Test
    void reasoning_then_tool_call_keeps_block_order() {
        List<StreamEvent> events = replay("reasoning-tool-calls.sse");
        AssistantMessage m = Fixtures.terminal(events);
        assertThat(m.content().get(0)).isInstanceOf(ContentBlock.Thinking.class);
        assertThat(m.toolCalls()).singleElement()
            .satisfies(c -> assertThat(c.arguments().path("path").asText()).isEqualTo("README.md"));
        assertThat(events).filteredOn(StreamEvent.ToolCallStart.class::isInstance)
            .extracting(e -> ((StreamEvent.ToolCallStart)e).index()).containsExactly(1);
    }

    @Test
    void usage_in_last_chunk_and_done_sentinel_with_length_stop() {
        AssistantMessage m = Fixtures.terminal(replay("usage-done.sse"));
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.LENGTH);
        assertThat(m.usage()).isEqualTo(new Usage(15L, 8L, 0L, 0L));
    }

    @Test
    void cache_fields_map_to_cache_read_and_are_excluded_from_input() {
        JsonNode openai = Json.mapper().createObjectNode().put("prompt_tokens", 100).put("completion_tokens", 7)
            .set("prompt_tokens_details", Json.mapper().createObjectNode().put("cached_tokens", 60));
        assertThat(OpenAiStreamDecoder.parseUsage(openai)).isEqualTo(new Usage(40L, 7L, 60L, 0L));
        JsonNode deepseek = Json.mapper().createObjectNode().put("prompt_tokens", 100).put("completion_tokens", 7)
            .put("prompt_cache_hit_tokens", 30).put("prompt_cache_miss_tokens", 70);
        assertThat(OpenAiStreamDecoder.parseUsage(deepseek)).isEqualTo(new Usage(70L, 7L, 30L, 0L));
        JsonNode kimi = Json.mapper().createObjectNode().put("prompt_tokens", 10).put("cached_tokens", 4);
        assertThat(OpenAiStreamDecoder.parseUsage(kimi)).isEqualTo(new Usage(6L, 0L, 4L, 0L));
    }

    @Test
    void alternative_reasoning_fields_and_details_are_thinking() {
        OpenAiCompletionsCodec.StreamDecoder d = codec.newDecoder();
        d.feed("{\"choices\":[{\"delta\":{\"reasoning\":\"a\"}}]}");
        d.feed("{\"choices\":[{\"delta\":{\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"b\"}]}}]}");
        d.feed("{\"choices\":[{\"delta\":{\"content\":\"c\"},\"finish_reason\":\"stop\"}]}");
        AssistantMessage m = Fixtures.terminal(d.finish());
        assertThat(m.content()).containsExactly(new ContentBlock.Thinking("a", java.util.Optional.of("reasoning")),
            new ContentBlock.Thinking("b", java.util.Optional.of("reasoning_details")), new ContentBlock.Text("c"));
    }

    @Test
    void empty_or_invalid_arguments_become_empty_object_with_diagnostic() {
        OpenAiCompletionsCodec.StreamDecoder d = codec.newDecoder();
        d.feed("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":"
            + "{\"name\":\"read\",\"arguments\":\"\"}}]}}]}");
        d.feed("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"c2\",\"function\":"
            + "{\"name\":\"bash\",\"arguments\":\"{oops\"}}]}}]}");
        d.feed("not json at all");
        d.feed("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        d.feed("data: [DONE]");
        List<StreamEvent> tail = d.finish();
        AssistantMessage m = Fixtures.terminal(tail);
        assertThat(m.toolCalls()).extracting(c -> c.arguments().toString()).containsExactly("{}", "{}");
        assertThat(m.errorMessage()).hasValueSatisfying(e -> assertThat(e).contains("c2").doesNotContain("c1"));
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.TOOL_USE);
    }

    @Test
    void error_object_terminates_with_error_event() {
        OpenAiCompletionsCodec.StreamDecoder d = codec.newDecoder();
        d.feed("{\"choices\":[{\"delta\":{\"content\":\"par\"}}]}");
        List<StreamEvent> events = d.feed("{\"error\":{\"message\":\"rate limited\",\"code\":\"429\"}}");
        StreamEvent.Error err = (StreamEvent.Error)events.get(events.size() - 1);
        assertThat(err.reason()).isEqualTo(StopReasonEnum.ERROR);
        assertThat(err.message()).isEqualTo("rate limited");
        assertThat(err.partial().orElseThrow().text()).isEqualTo("par");
        assertThat(d.finish()).isEmpty();
        assertThat(d.feed("{\"choices\":[{\"delta\":{\"content\":\"late\"}}]}")).isEmpty();
    }

    @Test
    void stream_cut_before_finish_is_an_error() {
        OpenAiCompletionsCodec.StreamDecoder d = codec.newDecoder();
        d.feed("{\"choices\":[{\"delta\":{\"content\":\"half\"}}]}");
        List<StreamEvent> tail = d.finish();
        assertThat(tail).singleElement().isInstanceOf(StreamEvent.Error.class);
        assertThat(Fixtures.terminal(tail).text()).isEqualTo("half");
    }

    @Test
    void request_body_matches_golden() {
        ObjectNode body = new OpenAiCompletionsCodec("deepseek", true).toRequestBody(
            RequestHistory.request("deepseek", "reasoning_content", Set.of(ModelRef.CapabilityEnum.TOOLS)));
        assertThat(body).isEqualTo(Fixtures.json("/golden/openai-request.json"));
    }

    @Test
    void reasoning_is_not_echoed_without_flag_and_images_pass_when_supported() {
        ObjectNode body = new OpenAiCompletionsCodec("openai-compat", false).toRequestBody(
            RequestHistory.request("openai-compat", "reasoning_content", Set.of(ModelRef.CapabilityEnum.IMAGE_INPUT)));
        JsonNode assistant = body.path("messages").path(2);
        assertThat(assistant.has("reasoning_content")).isFalse();
        JsonNode lastUser = body.path("messages").path(4).path("content");
        assertThat(lastUser.path(1).path("image_url").path("url").asText()).isEqualTo("data:image/png;base64,iVBORw0K");
    }
}
