/* pai-next — Apache-2.0 */
package dev.pai.core.skill;

import java.nio.charset.StandardCharsets;
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
 * {@code load_skill}: returns the body of a skill's {@code SKILL.md}, cut at {@link #MAX_BODY_BYTES}.
 */
final class LoadSkillTool implements Tool {

    /** Name of the tool. */
    static final String NAME = "load_skill";
    /** Largest body returned, in UTF-8 bytes. */
    static final int MAX_BODY_BYTES = 32 * 1024;

    private final SkillRegistry registry;

    LoadSkillTool(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME,
            "Load the full instructions of a skill listed in the system prompt. Call it before doing a task the skill "
                + "covers; files the skill mentions are relative to its directory.",
            JsonSchema.object().string("name", "Skill name as listed").required("name").build());
    }

    @Override
    public ToolEffectEnum effect() {
        return ToolEffectEnum.READ_ONLY;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) {
        String name = Args.of(arguments).string("name");
        Optional<Skill> skill = registry.get(name);
        if (skill.isEmpty()) {
            return ToolResult.error("unknown skill: " + name + "; available: "
                + String.join(", ", registry.all().stream().map(Skill::name).toList()));
        }
        String body = SkillRegistry.body(skill.get());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BODY_BYTES) {
            body = new String(bytes, 0, MAX_BODY_BYTES, StandardCharsets.UTF_8) + "\n\n[skill truncated: "
                + (bytes.length - MAX_BODY_BYTES) + " more bytes in " + skill.get().skillMd() + "]";
        }
        return ToolResult.text("Skill directory: " + skill.get().dir() + "\n\n" + body);
    }
}
