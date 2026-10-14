/* pai-next — Apache-2.0 */
package dev.pai.model.codec;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.AssistantMessage;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

/**
 * A fixed conversation with a tool round trip and a signed thinking block, used by the request body golden tests.
 */
public final class RequestHistory {

    private static final Instant T = Instant.parse("2026-10-15T00:00:00Z");

    private RequestHistory() {}

    /** Builds the request for a model with the given capabilities and thinking signature. */
    public static ChatRequest request(String providerId, String signature, Set<ModelRef.CapabilityEnum> caps) {
        ModelRef model =
            new ModelRef(providerId, "test-model", URI.create("https://example.invalid"), 128000, 4096, caps);
        ObjectNode schema = Json.mapper().createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("path").put("type", "string");
        schema.putArray("required").add("path");
        ObjectNode args = Json.mapper().createObjectNode().put("path", "README.md");
        AssistantMessage assistant = new AssistantMessage(
            List.of(new ContentBlock.Thinking("I should read the file.", Optional.of(signature)),
                new ContentBlock.Text("Reading it."), new ContentBlock.ToolCall("call_1", "read", args)),
            providerId, "test-model", Usage.ZERO, StopReasonEnum.TOOL_USE, Optional.empty(), T);
        ChatOptions options = new ChatOptions(OptionalInt.of(1024), OptionalDouble.of(0.2d),
            ChatOptions.ThinkingLevelEnum.OFF, Map.of("top_p", Json.mapper().getNodeFactory().numberNode(0.9d)));
        return new ChatRequest(model, Optional.of("You are a coding agent."),
            List.of(new UserMessage(List.of(new ContentBlock.Text("Summarize README.md")), T), assistant,
                new ToolResultMessage("call_1", "read", List.of(new ContentBlock.Text("1\t# pai-next")), false, T),
                new UserMessage(
                    List.of(new ContentBlock.Text("And this?"), new ContentBlock.Image("iVBORw0K", "image/png")), T)),
            List.of(new ToolDefinition("read", "Read a file.", schema)), options);
    }
}
