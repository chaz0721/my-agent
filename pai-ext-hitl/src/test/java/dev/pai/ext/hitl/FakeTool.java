/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.ToolDefinition;

/** A tool that only has a name and an effect. */
record FakeTool(String name, ToolEffectEnum effect) implements Tool {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name, "fake", JsonSchema.object().build());
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) {
        return ToolResult.text("ran");
    }

    static ContentBlock.ToolCall call(String name, String argsJson) {
        try {
            return new ContentBlock.ToolCall("c1", name, Json.mapper().readTree(argsJson));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
