/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ToolDefinition;

/**
 * Configurable tool for loop tests; records the arguments it was called with.
 */
final class TestTool implements Tool {

    /** Behaviour of one execution. */
    interface Body {
        ToolResult run(JsonNode args, ToolContext ctx) throws Exception;
    }

    private final String name;
    private final ToolEffectEnum effect;
    private final Body body;
    final List<JsonNode> calls = new CopyOnWriteArrayList<>();

    TestTool(String name, ToolEffectEnum effect, Body body) {
        this.name = name;
        this.effect = effect;
        this.body = body;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name, "test tool " + name,
            JsonSchema.object().string("path", "a path").required("path").build());
    }

    @Override
    public ToolEffectEnum effect() {
        return effect;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) throws Exception {
        calls.add(arguments);
        return body.run(arguments, ctx);
    }
}
