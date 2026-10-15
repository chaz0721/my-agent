/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.Args;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ToolDefinition;

/**
 * {@code recall(query?, topic?, limit?)}: reads a topic file, or searches every remembered fact (index and topic
 * files).
 */
final class RecallTool implements Tool {

    static final String NAME = "recall";
    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 20;
    static final int MAX_TOPIC_CHARS = 8_000;

    private final MemoryStore store;

    RecallTool(MemoryStore store) {
        this.store = store;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME,
            "Search facts and preferences remembered in earlier sessions, or read a memory topic file.",
            JsonSchema.object().string("query", "Words to look for").string("topic", "Topic file to read whole")
                .integer("limit", "Maximum results (default " + DEFAULT_LIMIT + ")").build());
    }

    @Override
    public ToolEffectEnum effect() {
        return ToolEffectEnum.READ_ONLY;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) {
        Args args = Args.of(arguments);
        int limit = Math.max(1, Math.min(MAX_LIMIT, args.intOr("limit", DEFAULT_LIMIT)));
        String topic = args.stringOr("topic", "").strip();
        if (!topic.isEmpty()) {
            Optional<String> text = MemoryStore.topicName(topic).flatMap(store::topic);
            if (text.isEmpty()) {
                List<String> all = new ArrayList<>(store.topics(MemoryStore.PROJECT));
                all.addAll(store.topics(MemoryStore.GLOBAL));
                return ToolResult.text("no topic " + topic + "; topics: " + (all.isEmpty() ? "none" : all));
            }
            String t = text.get();
            return ToolResult.text(t.length() <= MAX_TOPIC_CHARS ? t : t.substring(0, MAX_TOPIC_CHARS) + "\n[...]");
        }
        String query = args.stringOr("query", "");
        if (query.isBlank()) {
            return ToolResult.error("give a query or a topic");
        }
        List<Recall.Hit> hits = Recall.rank(store.all(), query, limit);
        if (hits.isEmpty()) {
            return ToolResult.text("no matching memories");
        }
        StringBuilder sb = new StringBuilder();
        hits.forEach(h -> sb.append(MemoryCommand.line(h.entry())).append('\n'));
        return ToolResult.text(sb.toString().stripTrailing());
    }
}
