/* pai-next — Apache-2.0 */
package dev.pai.core.skill;

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
 * {@code load_skill}: returns the body of a skill's {@code SKILL.md}, cut at {@link #MAX_BODY_CHARS} characters.
 */
final class LoadSkillTool implements Tool {

    /** Name of the tool. */
    static final String NAME = "load_skill";
    /** Largest body returned, in characters. */
    static final int MAX_BODY_CHARS = 32 * 1024;

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
        if (body.length() > MAX_BODY_CHARS) {
            int end = Character.isHighSurrogate(body.charAt(MAX_BODY_CHARS - 1)) ? MAX_BODY_CHARS - 1 : MAX_BODY_CHARS;
            body = body.substring(0, end) + "\n\n[skill truncated: " + (body.length() - end) + " more characters in "
                + skill.get().skillMd() + "]";
        }
        return ToolResult.text("Skill directory: " + skill.get().dir() + "\n\n" + body);
    }
}
