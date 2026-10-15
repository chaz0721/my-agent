/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ToolDefinition;

/** A {@code bash} stand-in that reports a few plain read commands as read-only, as the built-in one does. */
class FakeBashTool implements Tool {

    private static final Pattern READS =
        Pattern.compile("(ls|cat|git status|git log)( [^;&|<>$`'\"\\\\]*)?( \\| head)?");

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("bash", "fake", JsonSchema.object().build());
    }

    @Override
    public ToolEffectEnum effect() {
        return ToolEffectEnum.EXECUTE;
    }

    @Override
    public ToolEffectEnum effect(JsonNode input) {
        return READS.matcher(input.path("command").asText()).matches() ? ToolEffectEnum.READ_ONLY
            : ToolEffectEnum.EXECUTE;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) {
        return ToolResult.text("ran");
    }
}
