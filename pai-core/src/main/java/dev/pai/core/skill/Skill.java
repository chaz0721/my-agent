/* pai-next — Apache-2.0 */
package dev.pai.core.skill;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * A directory with a {@code SKILL.md}: its index entry is injected into the system prompt, its body is loaded on
 * demand.
 *
 * @param name skill name (frontmatter {@code name}, else the directory name)
 * @param description one-line description from the frontmatter
 * @param dir the skill directory
 * @param skillMd the {@code SKILL.md} file
 * @param frontmatter every frontmatter key
 */
public record Skill(String name, String description, Path dir, Path skillMd, Map<String, String> frontmatter) {

    /**
     * Copies the frontmatter and validates fields.
     */
    public Skill {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(skillMd, "skillMd");
        frontmatter = Map.copyOf(frontmatter);
    }
}
