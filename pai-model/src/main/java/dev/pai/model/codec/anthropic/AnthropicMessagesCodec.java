/* pai-next — Apache-2.0 */
package dev.pai.model.codec.anthropic;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.AssistantMessage;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.StreamEvent;
import dev.pai.model.SystemMessage;
import dev.pai.model.ToolDefinition;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.UserMessage;

/**
 * Translates neutral types to and from the Anthropic Messages wire format (streaming).
 *
 * <p>
 * Thinking signatures are sent back verbatim; redacted thinking is kept as a Thinking block whose signature is
 * {@value #REDACTED_PREFIX} followed by the opaque data.
 */
public final class AnthropicMessagesCodec {

    /** Signature prefix marking a redacted thinking block. */
    public static final String REDACTED_PREFIX = "redacted:";

    private static final int MIN_OUTPUT_AFTER_THINKING = 4096;
    private static final int BUDGET_LOW = 2048;
    private static final int BUDGET_MEDIUM = 8192;
    private static final int BUDGET_HIGH = 16384;

    private final String providerId;

    /**
     * Creates a codec.
     *
     * @param providerId provider id stamped on decoded messages
     */
    public AnthropicMessagesCodec(String providerId) {
        this.providerId = providerId;
    }

    /**
     * Builds a streaming request body.
     *
     * @param req the request
     * @return the JSON body
     */
    public ObjectNode toRequestBody(ChatRequest req) {
        ObjectNode body = Json.mapper().createObjectNode();
        ModelRef model = req.model();
        ChatOptions options = req.options();
        body.put("model", model.modelId());
        int maxTokens = options.maxTokens().orElse(model.maxOutputTokens());
        int budget = thinkingBudget(model, options.thinking());
        if (budget > 0) {
            maxTokens = Math.max(maxTokens, budget + MIN_OUTPUT_AFTER_THINKING);
        }
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        StringBuilder system = new StringBuilder(req.systemPrompt().orElse(""));
        ArrayNode messages = Json.mapper().createArrayNode();
        for (Message m : req.messages()) {
            if (m instanceof SystemMessage s) {
                system.append(system.isEmpty() ? "" : "\n\n").append(s.text());
            } else {
                appendMessage(messages, m);
            }
        }
        if (!system.isEmpty()) {
            body.put("system", system.toString());
        }
        body.set("messages", messages);
        if (budget > 0) {
            body.putObject("thinking").put("type", "enabled").put("budget_tokens", budget);
        } else {
            options.temperature().ifPresent(t -> body.put("temperature", t));
        }
        if (!req.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDefinition t : req.tools()) {
                tools.addObject().put("name", t.name()).put("description", t.description()).set("input_schema",
                    t.parametersSchema());
            }
        }
        for (Map.Entry<String, JsonNode> e : options.providerExtras().entrySet()) {
            body.set(e.getKey(), e.getValue());
        }
        return body;
    }

    /**
     * Creates a decoder whose messages take the model id from the stream.
     *
     * @return a fresh decoder
     */
    public StreamDecoder newDecoder() {
        return new AnthropicStreamDecoder(providerId, Optional.empty());
    }

    /**
     * Creates a decoder whose messages are stamped with the given model.
     *
     * @param model the requested model
     * @return a fresh decoder
     */
    public StreamDecoder newDecoder(ModelRef model) {
        return new AnthropicStreamDecoder(providerId, Optional.of(model.modelId()));
    }

    private static int thinkingBudget(ModelRef model, ChatOptions.ThinkingLevelEnum level) {
        if (!model.capabilities().contains(ModelRef.CapabilityEnum.THINKING)) {
            return 0;
        }
        return switch (level) {
            case OFF -> 0;
            case LOW -> BUDGET_LOW;
            case MEDIUM -> BUDGET_MEDIUM;
            case HIGH -> BUDGET_HIGH;
        };
    }

    private static void appendMessage(ArrayNode out, Message m) {
        switch (m) {
            case SystemMessage ignored -> {
                // folded into the top-level system field by the caller
            }
            case UserMessage u -> {
                ArrayNode content = userTurn(out);
                u.content().forEach(b -> appendUserBlock(content, b));
            }
            case AssistantMessage a -> appendAssistant(out, a);
            case ToolResultMessage r -> {
                ObjectNode block =
                    userTurn(out).addObject().put("type", "tool_result").put("tool_use_id", r.toolCallId());
                ArrayNode content = block.putArray("content");
                r.content().forEach(b -> appendUserBlock(content, b));
                if (content.isEmpty()) {
                    content.addObject().put("type", "text").put("text", "(no tool output)");
                }
                block.put("is_error", r.isError());
            }
        }
    }

    /** Returns the content array of the trailing user turn, opening one if the last turn is not a user turn. */
    private static ArrayNode userTurn(ArrayNode out) {
        if (!out.isEmpty()) {
            JsonNode last = out.get(out.size() - 1);
            if ("user".equals(last.path("role").asText())) {
                return (ArrayNode)last.path("content");
            }
        }
        ObjectNode turn = out.addObject().put("role", "user");
        return turn.putArray("content");
    }

    private static void appendUserBlock(ArrayNode content, ContentBlock b) {
        if (b instanceof ContentBlock.Text t && !t.text().isEmpty()) {
            content.addObject().put("type", "text").put("text", t.text());
        } else if (b instanceof ContentBlock.Image img) {
            content.addObject().put("type", "image").putObject("source").put("type", "base64")
                .put("media_type", img.mimeType()).put("data", img.base64());
        }
    }

    private static void appendAssistant(ArrayNode out, AssistantMessage a) {
        ArrayNode content = Json.mapper().createArrayNode();
        for (ContentBlock b : a.content()) {
            switch (b) {
                case ContentBlock.Text t -> {
                    if (!t.text().isBlank()) {
                        content.addObject().put("type", "text").put("text", t.text());
                    }
                }
                case ContentBlock.Thinking t -> appendThinking(content, t);
                case ContentBlock.ToolCall c -> content.addObject().put("type", "tool_use").put("id", c.id())
                    .put("name", c.name()).set("input", c.arguments());
                case ContentBlock.Image ignored -> {
                    // assistants never produce images
                }
            }
        }
        if (!content.isEmpty()) {
            out.addObject().put("role", "assistant").set("content", content);
        }
    }

    private static void appendThinking(ArrayNode content, ContentBlock.Thinking t) {
        String sig = t.signature().orElse("");
        if (sig.startsWith(REDACTED_PREFIX)) {
            content.addObject().put("type", "redacted_thinking").put("data", sig.substring(REDACTED_PREFIX.length()));
        } else if (!sig.isBlank()) {
            content.addObject().put("type", "thinking").put("thinking", t.text()).put("signature", sig);
        } else if (!t.text().isBlank()) {
            content.addObject().put("type", "text").put("text", t.text());
        }
    }

    /**
     * Stateful decoder: feed SSE data payloads, receive stream events.
     */
    public interface StreamDecoder {

        /**
         * Consumes one SSE data payload (with or without the {@code data:} prefix).
         *
         * @param sseDataLine the payload
         * @return events produced, possibly empty
         */
        List<StreamEvent> feed(String sseDataLine);

        /**
         * Signals end of input; emits the terminal event unless one was already emitted.
         *
         * @return remaining events
         */
        List<StreamEvent> finish();

        /**
         * Terminates the stream with an error carrying the partial message, unless already terminated.
         *
         * @param reason ERROR or ABORTED
         * @param message diagnostic
         * @return the terminal event, or nothing when already terminated
         */
        List<StreamEvent> fail(StopReasonEnum reason, String message);
    }
}
