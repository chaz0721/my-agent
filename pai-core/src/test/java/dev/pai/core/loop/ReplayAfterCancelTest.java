/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.ToolRegistry;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.StreamEvent;
import dev.pai.model.UserMessage;
import dev.pai.model.codec.anthropic.AnthropicMessagesCodec;
import dev.pai.model.codec.openai.OpenAiCompletionsCodec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run cancelled while a tool call is streaming leaves an ABORTED reply with a ToolCall and no result; the next
 * request of either codec must not contain that dangling call.
 */
class ReplayAfterCancelTest {

    @TempDir
    Path cwd;

    @Test
    void cancel_during_tool_use_stream_keeps_next_request_valid() {
        ContentBlock.ToolCall call =
            new ContentBlock.ToolCall("toolu_1", "read", Json.mapper().createObjectNode().put("path", "a.txt"));
        FakeProviderImpl provider = FakeProviderImpl.scripted(
            List.of(FakeProviderImpl.reply(List.of(new ContentBlock.Text("Reading."), call), StopReasonEnum.TOOL_USE)));
        ModelRef model = new ModelRef("fake", "m", URI.create("http://fake.invalid"), 1000, 100,
            Set.of(ModelRef.CapabilityEnum.TOOLS));
        CancelTokenSource cancel = new CancelTokenSource();
        List<Message> added =
            new AgentLoop(provider, new ToolRegistry(), new AgentHooks() {}, AgentConfig.of(model, "s"), cwd)
                .run(List.of(), UserMessage.text("read a.txt"), e -> {
                    if (e instanceof AgentEvent.MessageUpdate u && u.event() instanceof StreamEvent.ToolCallStart) {
                        cancel.cancel();
                    }
                }, cancel);
        AssistantMessage aborted = (AssistantMessage)added.get(1);
        assertThat(aborted.stopReason()).isEqualTo(StopReasonEnum.ABORTED);
        assertThat(aborted.toolCalls()).containsExactly(call);

        List<Message> history = new ArrayList<>(added);
        history.add(UserMessage.text("try again"));
        ChatRequest next =
            new ChatRequest(model, java.util.Optional.empty(), history, List.of(), ChatOptions.defaults());
        JsonNode openai = new OpenAiCompletionsCodec("fake", true).toRequestBody(next);
        JsonNode anthropic = new AnthropicMessagesCodec("fake").toRequestBody(next);
        assertThat(openai.toString()).doesNotContain("toolu_1", "tool_calls");
        assertThat(anthropic.toString()).doesNotContain("toolu_1", "tool_use");
        assertThat(openai.path("messages")).extracting(n -> n.path("role").asText()).containsExactly("user", "user");
        assertThat(anthropic.path("messages")).hasSize(1);
    }
}
