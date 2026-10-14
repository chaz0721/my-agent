/* pai-next — Apache-2.0 */
package dev.pai.model.codec.anthropic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Messages API SSE decoder: message_start, content_block_start/delta/stop, message_delta, message_stop, ping, error.
 */
final class AnthropicStreamDecoder implements StreamDecoder {

    private static final String DATA_PREFIX = "data:";

    private final String providerId;
    private final boolean modelFixed;
    private final List<Block> blocks = new ArrayList<>();
    private final Map<Integer, Integer> blockByWireIndex = new HashMap<>();
    private final Map<String, String> diagnostics = new LinkedHashMap<>();
    private String modelId;
    private long input;
    private long output;
    private long cacheRead;
    private long cacheWrite;
    private Optional<String> stopReason = Optional.empty();
    private boolean started;
    private boolean terminated;

    AnthropicStreamDecoder(String providerId, Optional<String> modelId) {
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
        JsonNode ev;
        try {
            ev = payload.isEmpty() ? null : Json.mapper().readTree(payload);
        } catch (JsonProcessingException e) {
            ev = null;
        }
        if (ev == null || !ev.isObject()) {
            return out;
        }
        switch (ev.path("type").asText()) {
            case "message_start" -> onMessageStart(ev.path("message"));
            case "content_block_start" -> onBlockStart(ev, out);
            case "content_block_delta" -> onBlockDelta(ev, out);
            case "content_block_stop" -> onBlockStop(ev, out);
            case "message_delta" -> onMessageDelta(ev);
            case "message_stop" -> out.addAll(complete());
            case "error" -> out.addAll(
                fail(StopReasonEnum.ERROR, ev.path("error").path("message").asText(ev.path("error").toString())));
            default -> {
                // ping and unknown events carry nothing we need
            }
        }
        return out;
    }

    private void onMessageStart(JsonNode message) {
        if (!modelFixed && message.path("model").isTextual()) {
            modelId = message.path("model").asText();
        }
        readUsage(message.path("usage"));
    }

    private void readUsage(JsonNode u) {
        input = u.path("input_tokens").asLong(input);
        output = u.path("output_tokens").asLong(output);
        cacheRead = u.path("cache_read_input_tokens").asLong(cacheRead);
        cacheWrite = u.path("cache_creation_input_tokens").asLong(cacheWrite);
    }

    private void onBlockStart(JsonNode ev, List<StreamEvent> out) {
        JsonNode cb = ev.path("content_block");
        Block b = new Block(cb.path("type").asText());
        blocks.add(b);
        int idx = blocks.size() - 1;
        blockByWireIndex.put(ev.path("index").asInt(idx), idx);
        switch (b.type) {
            case "text" -> appendText(idx, b, cb.path("text").asText(""), out);
            case "thinking" -> {
                b.signature.append(cb.path("signature").asText(""));
                String t = cb.path("thinking").asText("");
                if (!t.isEmpty()) {
                    b.text.append(t);
                    out.add(new StreamEvent.ThinkingDelta(idx, t));
                }
            }
            case "redacted_thinking" ->
                b.signature.append(AnthropicMessagesCodec.REDACTED_PREFIX).append(cb.path("data").asText(""));
            case "tool_use" -> {
                b.id = cb.path("id").asText("");
                b.name = cb.path("name").asText("");
                JsonNode input = cb.path("input");
                if (input.isObject() && !input.isEmpty()) {
                    b.prefill = input.toString();
                }
                out.add(new StreamEvent.ToolCallStart(idx, b.id, b.name));
            }
            default -> {
                // server-side block types are kept as empty text
            }
        }
    }

    private static void appendText(int idx, Block b, String text, List<StreamEvent> out) {
        if (!text.isEmpty()) {
            b.text.append(text);
            out.add(new StreamEvent.TextDelta(idx, text));
        }
    }

    private void onBlockDelta(JsonNode ev, List<StreamEvent> out) {
        Integer idx = blockByWireIndex.get(ev.path("index").asInt(-1));
        if (idx == null) {
            return;
        }
        Block b = blocks.get(idx);
        JsonNode d = ev.path("delta");
        switch (d.path("type").asText()) {
            case "text_delta" -> appendText(idx, b, d.path("text").asText(""), out);
            case "thinking_delta" -> {
                String t = d.path("thinking").asText("");
                b.text.append(t);
                out.add(new StreamEvent.ThinkingDelta(idx, t));
            }
            case "signature_delta" -> b.signature.append(d.path("signature").asText(""));
            case "input_json_delta" -> {
                String part = d.path("partial_json").asText("");
                b.text.append(part);
                out.add(new StreamEvent.ToolCallDelta(idx, part));
            }
            default -> {
                // citations and other deltas are not modelled
            }
        }
    }

    private void onBlockStop(JsonNode ev, List<StreamEvent> out) {
        Integer idx = blockByWireIndex.get(ev.path("index").asInt(-1));
        if (idx == null || !"tool_use".equals(blocks.get(idx).type)) {
            return;
        }
        Block b = blocks.get(idx);
        b.call = toolCall(b, diagnostics);
        out.add(new StreamEvent.ToolCallEnd(idx, b.call));
    }

    private void onMessageDelta(JsonNode ev) {
        if (ev.path("delta").path("stop_reason").isTextual()) {
            stopReason = Optional.of(ev.path("delta").path("stop_reason").asText());
        }
        readUsage(ev.path("usage"));
    }

    private List<StreamEvent> complete() {
        String reason = stopReason.orElse("end_turn");
        StopReasonEnum stop = switch (reason) {
            case "end_turn", "stop_sequence", "pause_turn" -> StopReasonEnum.STOP;
            case "max_tokens" -> StopReasonEnum.LENGTH;
            case "tool_use" -> StopReasonEnum.TOOL_USE;
            default -> StopReasonEnum.ERROR;
        };
        if (stop == StopReasonEnum.ERROR) {
            return fail(StopReasonEnum.ERROR, "provider stop_reason: " + reason);
        }
        List<StreamEvent> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if ("tool_use".equals(b.type) && b.call == null) {
                b.call = toolCall(b, diagnostics);
                out.add(new StreamEvent.ToolCallEnd(i, b.call));
            }
        }
        terminated = true;
        out.add(new StreamEvent.Done(message(stop, Optional.empty())));
        return out;
    }

    @Override
    public List<StreamEvent> finish() {
        List<StreamEvent> out = new ArrayList<>();
        if (terminated) {
            return out;
        }
        ensureStarted(out);
        out.addAll(fail(StopReasonEnum.ERROR, "stream ended before message_stop"));
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
        out.add(new StreamEvent.Error(reason, message, Optional.of(message(reason, Optional.of(message)))));
        return out;
    }

    private AssistantMessage message(StopReasonEnum stop, Optional<String> err) {
        List<ContentBlock> content = new ArrayList<>(blocks.size());
        for (Block b : blocks) {
            content.add(switch (b.type) {
                case "thinking", "redacted_thinking" -> new ContentBlock.Thinking(b.text.toString(),
                    b.signature.isEmpty() ? Optional.empty() : Optional.of(b.signature.toString()));
                case "tool_use" -> b.call != null ? b.call : toolCall(b, new LinkedHashMap<>());
                default -> new ContentBlock.Text(b.text.toString());
            });
        }
        Usage usage = new Usage(input, output, cacheRead, cacheWrite);
        return new AssistantMessage(content, providerId, modelId, usage, stop, err, Instant.now(),
            Optional.of(diagnostics));
    }

    private static ContentBlock.ToolCall toolCall(Block b, Map<String, String> diagnostics) {
        String raw = b.text.isEmpty() ? b.prefill : b.text.toString();
        Optional<? extends JsonNode> args = Json.parseObject(raw);
        if (args.isEmpty()) {
            diagnostics.put(b.id, AssistantMessage.invalidArgumentsDiagnostic(b.id, b.name, raw));
        }
        JsonNode value = args.isPresent() ? args.get() : Json.mapper().createObjectNode();
        return new ContentBlock.ToolCall(b.id, b.name, value);
    }

    private void ensureStarted(List<StreamEvent> out) {
        if (!started) {
            started = true;
            out.add(new StreamEvent.Start());
        }
    }

    /**
     * A content block under construction.
     */
    private static final class Block {
        private final String type;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder signature = new StringBuilder();
        private String id = "";
        private String name = "";
        private String prefill = "";
        private ContentBlock.ToolCall call;

        private Block(String type) {
            this.type = type;
        }
    }
}
