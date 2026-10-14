/* pai-next — Apache-2.0 */
package dev.pai.model.codec.openai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.StreamEvent;
import dev.pai.model.Usage;
import dev.pai.model.codec.StreamDecoder;

/**
 * Chat Completions SSE decoder. Tool call fragments are keyed by {@code delta.tool_calls[].index}; reasoning may arrive
 * as {@code reasoning_content}, {@code reasoning}, {@code reasoning_text} or {@code reasoning_details[]}.
 */
final class OpenAiStreamDecoder implements StreamDecoder {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_SENTINEL = "[DONE]";

    private final String providerId;
    private final List<Block> blocks = new ArrayList<>();
    private final Map<Integer, Integer> toolBlockByWireIndex = new HashMap<>();
    private String modelId;
    private final boolean modelFixed;
    private Usage usage = Usage.ZERO;
    private int lastWireIndex;
    private Optional<String> finishReason = Optional.empty();
    private boolean sawDone;
    private boolean started;
    private boolean terminated;

    OpenAiStreamDecoder(String providerId, Optional<String> modelId) {
        this.providerId = providerId;
        this.modelId = modelId.orElse("");
        this.modelFixed = modelId.isPresent();
    }

    @Override
    public List<StreamEvent> feed(String sseDataLine) {
        List<StreamEvent> out = new ArrayList<>();
        if (terminated) {
            return out;
        }
        ensureStarted(out);
        String payload = sseDataLine.strip();
        if (payload.startsWith(DATA_PREFIX)) {
            payload = payload.substring(DATA_PREFIX.length()).strip();
        }
        if (payload.isEmpty()) {
            return out;
        }
        if (DONE_SENTINEL.equals(payload)) {
            sawDone = true;
            return out;
        }
        JsonNode root;
        try {
            root = Json.mapper().readTree(payload);
        } catch (JsonProcessingException e) {
            return out;
        }
        JsonNode error = root.path("error");
        if (error.isObject() || error.isTextual()) {
            String msg = error.isTextual() ? error.asText() : error.path("message").asText(error.toString());
            out.addAll(fail(StopReasonEnum.ERROR, msg));
            return out;
        }
        onChunk(root, out);
        return out;
    }

    private void onChunk(JsonNode root, List<StreamEvent> out) {
        if (!modelFixed && root.path("model").isTextual()) {
            modelId = root.path("model").asText();
        }
        if (root.path("usage").isObject()) {
            usage = parseUsage(root.path("usage"));
        }
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            return;
        }
        if (choice.path("finish_reason").isTextual()) {
            finishReason = Optional.of(choice.path("finish_reason").asText());
        }
        JsonNode delta = choice.has("delta") ? choice.path("delta") : choice.path("message");
        if (!delta.isObject()) {
            return;
        }
        onReasoning(delta, out);
        String text = delta.path("content").isTextual() ? delta.path("content").asText() : "";
        if (!text.isEmpty()) {
            int idx = openBlock(KindEnum.TEXT, "");
            blocks.get(idx).text.append(text);
            out.add(new StreamEvent.TextDelta(idx, text));
        }
        for (JsonNode tc : delta.path("tool_calls")) {
            onToolCallDelta(tc, out);
        }
    }

    private void onReasoning(JsonNode delta, List<StreamEvent> out) {
        for (String field : List.of("reasoning_content", "reasoning", "reasoning_text")) {
            String r = delta.path(field).isTextual() ? delta.path(field).asText() : "";
            if (!r.isEmpty()) {
                appendThinking(field, r, out);
                return;
            }
        }
        StringBuilder details = new StringBuilder();
        for (JsonNode d : delta.path("reasoning_details")) {
            details.append(d.path("text").asText(d.path("summary").asText("")));
        }
        if (!details.isEmpty()) {
            appendThinking("reasoning_details", details.toString(), out);
        }
    }

    private void appendThinking(String field, String text, List<StreamEvent> out) {
        int idx = openBlock(KindEnum.THINKING, field);
        blocks.get(idx).text.append(text);
        out.add(new StreamEvent.ThinkingDelta(idx, text));
    }

    private void onToolCallDelta(JsonNode tc, List<StreamEvent> out) {
        int wireIndex = tc.path("index").isInt() ? tc.path("index").asInt() : lastWireIndex;
        lastWireIndex = wireIndex;
        String id = tc.path("id").asText("");
        Integer idx = toolBlockByWireIndex.get(wireIndex);
        boolean newCall =
            idx == null || !id.isEmpty() && !blocks.get(idx).id.isEmpty() && !id.equals(blocks.get(idx).id);
        JsonNode fn = tc.path("function");
        if (newCall) {
            Block b = new Block(KindEnum.TOOL, "");
            b.id = id;
            b.name = fn.path("name").asText("");
            blocks.add(b);
            idx = blocks.size() - 1;
            toolBlockByWireIndex.put(wireIndex, idx);
            out.add(new StreamEvent.ToolCallStart(idx, b.id, b.name));
        } else {
            Block b = blocks.get(idx);
            if (b.id.isEmpty()) {
                b.id = id;
            }
            if (b.name.isEmpty()) {
                b.name = fn.path("name").asText("");
            }
        }
        String args = fn.path("arguments").isTextual() ? fn.path("arguments").asText() : "";
        if (!args.isEmpty()) {
            blocks.get(idx).text.append(args);
            out.add(new StreamEvent.ToolCallDelta(idx, args));
        }
    }

    private int openBlock(KindEnum kind, String field) {
        if (!blocks.isEmpty()) {
            Block last = blocks.get(blocks.size() - 1);
            if (last.kind == kind && last.field.equals(field)) {
                return blocks.size() - 1;
            }
        }
        blocks.add(new Block(kind, field));
        return blocks.size() - 1;
    }

    @Override
    public List<StreamEvent> finish() {
        List<StreamEvent> out = new ArrayList<>();
        if (terminated) {
            return out;
        }
        ensureStarted(out);
        if (finishReason.isEmpty() && !sawDone) {
            out.addAll(fail(StopReasonEnum.ERROR, "stream ended before completion"));
            return out;
        }
        boolean hasTools = blocks.stream().anyMatch(b -> b.kind == KindEnum.TOOL);
        String reason = finishReason.orElse(hasTools ? "tool_calls" : "stop");
        StopReasonEnum stop = mapStopReason(reason, hasTools);
        if (stop == StopReasonEnum.ERROR) {
            out.addAll(fail(StopReasonEnum.ERROR, "provider finish_reason: " + reason));
            return out;
        }
        List<String> diagnostics = new ArrayList<>();
        List<ContentBlock> content = buildContent(diagnostics);
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i) instanceof ContentBlock.ToolCall call) {
                out.add(new StreamEvent.ToolCallEnd(i, call));
            }
        }
        Optional<String> err = diagnostics.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", diagnostics));
        terminated = true;
        out.add(new StreamEvent.Done(message(content, stop, err)));
        return out;
    }

    @Override
    public List<StreamEvent> fail(StopReasonEnum reason, String message) {
        List<StreamEvent> out = new ArrayList<>();
        if (terminated) {
            return out;
        }
        ensureStarted(out);
        terminated = true;
        AssistantMessage partial = message(buildContent(new ArrayList<>()), reason, Optional.of(message));
        out.add(new StreamEvent.Error(reason, message, Optional.of(partial)));
        return out;
    }

    private List<ContentBlock> buildContent(List<String> diagnostics) {
        List<ContentBlock> content = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            content.add(switch (b.kind) {
                case TEXT -> new ContentBlock.Text(b.text.toString());
                case THINKING -> new ContentBlock.Thinking(b.text.toString(), Optional.of(b.field));
                case TOOL -> toolCall(i, b, diagnostics);
            });
        }
        return content;
    }

    private static ContentBlock.ToolCall toolCall(int index, Block b, List<String> diagnostics) {
        String id = b.id.isEmpty() ? "call_" + index : b.id;
        Optional<? extends JsonNode> args = Json.parseObject(b.text.toString());
        if (args.isEmpty()) {
            diagnostics.add(AssistantMessage.invalidArgumentsDiagnostic(id, b.name, b.text.toString()));
        }
        JsonNode value = args.isPresent() ? args.get() : Json.mapper().createObjectNode();
        return new ContentBlock.ToolCall(id, b.name, value);
    }

    private AssistantMessage message(List<ContentBlock> content, StopReasonEnum stop, Optional<String> err) {
        return new AssistantMessage(content, providerId, modelId, usage, stop, err, Instant.now());
    }

    private void ensureStarted(List<StreamEvent> out) {
        if (!started) {
            started = true;
            out.add(new StreamEvent.Start());
        }
    }

    static StopReasonEnum mapStopReason(String reason, boolean hasTools) {
        return switch (reason) {
            case "stop", "end" -> hasTools ? StopReasonEnum.TOOL_USE : StopReasonEnum.STOP;
            case "length" -> StopReasonEnum.LENGTH;
            case "tool_calls", "function_call" -> StopReasonEnum.TOOL_USE;
            default -> StopReasonEnum.ERROR;
        };
    }

    static Usage parseUsage(JsonNode u) {
        long prompt = u.path("prompt_tokens").asLong(0L);
        JsonNode details = u.path("prompt_tokens_details");
        long cacheRead;
        if (details.path("cached_tokens").isNumber()) {
            cacheRead = details.path("cached_tokens").asLong();
        } else if (u.path("prompt_cache_hit_tokens").isNumber()) {
            cacheRead = u.path("prompt_cache_hit_tokens").asLong();
        } else {
            cacheRead = u.path("cached_tokens").asLong(0L);
        }
        long cacheWrite = details.path("cache_write_tokens").asLong(0L);
        long input = Math.max(0L, prompt - cacheRead - cacheWrite);
        return new Usage(input, u.path("completion_tokens").asLong(0L), cacheRead, cacheWrite);
    }

    /**
     * Kind of an accumulating content block.
     */
    private enum KindEnum {
        /** Visible text. */
        TEXT,
        /** Reasoning text. */
        THINKING,
        /** Tool call with streamed JSON arguments. */
        TOOL
    }

    /**
     * A content block under construction.
     */
    private static final class Block {
        private final KindEnum kind;
        private final String field;
        private final StringBuilder text = new StringBuilder();
        private String id = "";
        private String name = "";

        private Block(KindEnum kind, String field) {
            this.kind = kind;
            this.field = field;
        }
    }
}
