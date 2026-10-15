/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.Args;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ToolDefinition;

/**
 * {@code recall(query, limit?)}: searches remembered facts.
 */
final class RecallTool implements Tool {

    static final String NAME = "recall";
    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 20;

    private final MemoryStore store;

    RecallTool(MemoryStore store) {
        this.store = store;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME, "Search facts and preferences remembered in earlier sessions.",
            JsonSchema.object().string("query", "Words to look for")
                .integer("limit", "Maximum results (default " + DEFAULT_LIMIT + ")").required("query").build());
    }

    @Override
    public ToolEffectEnum effect() {
        return ToolEffectEnum.READ_ONLY;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) {
        Args args = Args.of(arguments);
        int limit = Math.max(1, Math.min(MAX_LIMIT, args.intOr("limit", DEFAULT_LIMIT)));
        List<Recall.Hit> hits = Recall.rank(store.list(), args.string("query"), limit);
        if (hits.isEmpty()) {
            return ToolResult.text("no matching memories");
        }
        StringBuilder sb = new StringBuilder();
        hits.forEach(h -> sb.append(MemoryCommand.line(h.entry())).append('\n'));
        return ToolResult.text(sb.toString().stripTrailing());
    }
}
