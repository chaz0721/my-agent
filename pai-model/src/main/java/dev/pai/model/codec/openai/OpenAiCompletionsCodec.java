/* pai-next — Apache-2.0 */
package dev.pai.model.codec.openai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
import dev.pai.model.SystemMessage;
import dev.pai.model.ToolDefinition;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.UserMessage;
import dev.pai.model.codec.StreamDecoder;

/**
 * Translates neutral types to and from the OpenAI Chat Completions wire format (streaming).
 *
 * <p>
 * Thinking blocks decoded by this codec carry the name of the wire field they came from as signature
 * ({@code reasoning_content}, {@code reasoning}, ...). With {@code echoReasoning} the text is written back to that
 * field on the next request, which DeepSeek and Kimi require during tool use; only replies of the same provider and
 * model are echoed, since presets share a wire format but not their reasoning protocol.
 */
public final class OpenAiCompletionsCodec {

    /** Wire fields that carry reasoning text. */
    static final Set<String> REASONING_FIELDS = Set.of("reasoning_content", "reasoning", "reasoning_text");

    private static final String IMAGE_OMITTED = "(image omitted: the model does not accept image input)";
    private static final String NO_OUTPUT = "(no tool output)";

    private final String providerId;
    private final boolean echoReasoning;

    /**
     * Creates a codec.
     *
     * @param providerId provider id stamped on decoded messages
     * @param echoReasoning whether to send reasoning text back on assistant messages
     */
    public OpenAiCompletionsCodec(String providerId, boolean echoReasoning) {
        this.providerId = providerId;
        this.echoReasoning = echoReasoning;
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
        body.put("model", model.modelId());
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        ChatOptions options = req.options();
        options.maxTokens().ifPresent(n -> body.put("max_tokens", n));
        options.temperature().ifPresent(t -> body.put("temperature", t));
        boolean thinkingModel = model.capabilities().contains(ModelRef.CapabilityEnum.THINKING);
        if (thinkingModel && options.thinking() != ChatOptions.ThinkingLevelEnum.OFF) {
            body.put("reasoning_effort", options.thinking().name().toLowerCase(Locale.ROOT));
        }
        ArrayNode messages = body.putArray("messages");
        req.systemPrompt().ifPresent(s -> messages.addObject().put("role", "system").put("content", s));
        boolean images = model.capabilities().contains(ModelRef.CapabilityEnum.IMAGE_INPUT);
        appendHistory(messages, req.messages(), model, images);
        if (!req.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDefinition t : req.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", t.name()).put("description", t.description()).set("parameters", t.parametersSchema());
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
        return new OpenAiStreamDecoder(providerId, Optional.empty());
    }

    /**
     * Creates a decoder whose messages are stamped with the given model.
     *
     * @param model the requested model
     * @return a fresh decoder
     */
    public StreamDecoder newDecoder(ModelRef model) {
        return new OpenAiStreamDecoder(providerId, Optional.of(model.modelId()));
    }

    private void appendHistory(ArrayNode out, List<Message> history, ModelRef model, boolean images) {
        Set<String> answerable = new HashSet<>();
        int i = 0;
        while (i < history.size()) {
            Message m = history.get(i);
            if (m instanceof ToolResultMessage) {
                i = appendToolResults(out, history, i, answerable, images);
                continue;
            }
            switch (m) {
                case SystemMessage s -> out.addObject().put("role", "system").put("content", s.text());
                case UserMessage u -> appendUser(out, u.content(), images);
                case AssistantMessage a -> appendAssistant(out, a, model, answerable);
                case ToolResultMessage r -> throw new IllegalStateException("handled above");
            }
            i++;
        }
    }

    private static void appendUser(ArrayNode out, List<ContentBlock> content, boolean images) {
        ObjectNode msg = out.addObject().put("role", "user");
        boolean textOnly = content.stream().allMatch(ContentBlock.Text.class::isInstance);
        if (textOnly) {
            msg.put("content", joinText(content));
            return;
        }
        ArrayNode parts = msg.putArray("content");
        for (ContentBlock b : content) {
            if (b instanceof ContentBlock.Text t) {
                parts.addObject().put("type", "text").put("text", t.text());
            } else if (b instanceof ContentBlock.Image img) {
                if (images) {
                    parts.addObject().put("type", "image_url").putObject("image_url").put("url",
                        "data:" + img.mimeType() + ";base64," + img.base64());
                } else {
                    parts.addObject().put("type", "text").put("text", IMAGE_OMITTED);
                }
            }
        }
    }

    private void appendAssistant(ArrayNode out, AssistantMessage a, ModelRef model, Set<String> answerable) {
        if (!a.replayable()) {
            return;
        }
        String text = joinText(a.content());
        List<ContentBlock.ToolCall> calls = a.toolCalls();
        if (text.isEmpty() && calls.isEmpty()) {
            return;
        }
        ObjectNode msg = out.addObject().put("role", "assistant");
        if (text.isEmpty()) {
            msg.putNull("content");
        } else {
            msg.put("content", text);
        }
        if (echoReasoning && model.providerId().equals(a.providerId()) && model.modelId().equals(a.modelId())) {
            appendReasoning(msg, a.content());
        }
        if (!calls.isEmpty()) {
            ArrayNode tc = msg.putArray("tool_calls");
            for (ContentBlock.ToolCall c : calls) {
                answerable.add(c.id());
                ObjectNode fn = tc.addObject().put("id", c.id()).put("type", "function").putObject("function");
                fn.put("name", c.name()).put("arguments", c.arguments().toString());
            }
        }
    }

    private static void appendReasoning(ObjectNode msg, List<ContentBlock> content) {
        List<ContentBlock.Thinking> thinking =
            content.stream().filter(ContentBlock.Thinking.class::isInstance).map(ContentBlock.Thinking.class::cast)
                .filter(t -> t.signature().filter(REASONING_FIELDS::contains).isPresent()).toList();
        if (thinking.isEmpty()) {
            return;
        }
        String field = thinking.get(0).signature().orElseThrow();
        msg.put(field, thinking.stream().map(ContentBlock.Thinking::text).collect(Collectors.joining("\n")));
    }

    /** Appends a run of tool results, then one user message with all their images; returns the next index. */
    private static int appendToolResults(ArrayNode out, List<Message> history, int start, Set<String> answerable,
        boolean images) {
        List<ContentBlock> imgs = new ArrayList<>();
        int i = start;
        while (i < history.size() && history.get(i) instanceof ToolResultMessage r) {
            i++;
            if (!answerable.remove(r.toolCallId())) {
                continue;
            }
            List<ContentBlock> own = r.content().stream().filter(ContentBlock.Image.class::isInstance).toList();
            imgs.addAll(own);
            String text = joinText(r.content());
            String wire = text.isEmpty() ? (own.isEmpty() ? NO_OUTPUT : "(see attached image)") : text;
            out.addObject().put("role", "tool").put("tool_call_id", r.toolCallId()).put("content", wire);
        }
        if (!imgs.isEmpty()) {
            appendUser(out, imgs, images);
        }
        return i;
    }

    private static String joinText(List<ContentBlock> content) {
        return content.stream().filter(ContentBlock.Text.class::isInstance).map(b -> ((ContentBlock.Text)b).text())
            .collect(Collectors.joining("\n"));
    }
}
