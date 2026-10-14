/* pai-next — Apache-2.0 */
package dev.pai.core.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skills found in an ordered list of root directories; every {@code <root>/
 * 
<dir>
 * /SKILL.md} is a skill and a later root overrides an earlier one with the same name (bundled, then
 * {@code ~/.pai/skills}, then {@code <cwd>/.pai/skills}).
 */
public final class SkillRegistry {

    /** Default {@code maxSkills} of {@link #indexText}. */
    public static final int DEFAULT_INDEX_SKILLS = 20;
    /** Default {@code maxChars} of {@link #indexText}. */
    public static final int DEFAULT_INDEX_CHARS = 4_000;

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private static final String SKILL_FILE = "SKILL.md";
    private static final int MAX_DESCRIPTION_CHARS = 200;

    private final List<Path> roots;
    private Map<String, Skill> skills = Map.of();

    /**
     * Creates a registry; call {@link #reload()} to scan.
     *
     * @param roots skill roots, lowest precedence first; missing directories are ignored
     */
    public SkillRegistry(List<Path> roots) {
        this.roots = List.copyOf(roots);
    }

    /**
     * Rescans every root.
     */
    public synchronized void reload() {
        Map<String, Skill> found = new TreeMap<>();
        for (Path root : roots) {
            for (Path dir : skillDirs(root)) {
                read(dir).ifPresent(s -> found.put(s.name(), s));
            }
        }
        skills = Map.copyOf(found);
    }

    /**
     * Returns all skills sorted by name.
     *
     * @return the skills
     */
    public synchronized List<Skill> all() {
        return new TreeMap<>(skills).values().stream().toList();
    }

    /**
     * Looks a skill up by name.
     *
     * @param name the name
     * @return the skill
     */
    public synchronized Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * Renders the index injected into the system prompt: one {@code - name: description} line per skill, cut at
     * {@code maxSkills} entries or {@code maxChars} characters with a note on how many were left out.
     *
     * @param maxSkills maximum entries
     * @param maxChars maximum characters
     * @return the index, empty when there are no skills
     */
    public String indexText(int maxSkills, int maxChars) {
        List<Skill> list = all();
        if (list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
            "Skills are task-specific instructions. When a task matches a skill, call load_skill with its name first.\n");
        int shown = 0;
        for (Skill s : list) {
            String desc = s.description().length() > MAX_DESCRIPTION_CHARS
                ? s.description().substring(0, MAX_DESCRIPTION_CHARS) + "..." : s.description();
            String line = "- " + s.name() + (desc.isEmpty() ? "" : ": " + desc) + "\n";
            if (shown >= maxSkills || sb.length() + line.length() > maxChars) {
                break;
            }
            sb.append(line);
            shown++;
        }
        if (shown < list.size()) {
            sb.append("(").append(list.size() - shown)
                .append(" more skills not listed; load_skill accepts any name)\n");
        }
        return sb.toString().strip();
    }

    private static List<Path> skillDirs(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(d -> Files.isRegularFile(d.resolve(SKILL_FILE))).sorted().toList();
        } catch (IOException e) {
            log.warn("cannot scan skill root {}", root, e);
            return List.of();
        }
    }

    private static Optional<Skill> read(Path dir) {
        Path md = dir.resolve(SKILL_FILE);
        try {
            Frontmatter fm = Frontmatter.parse(Files.readString(md, StandardCharsets.UTF_8));
            String name = fm.values().getOrDefault("name", "").isBlank() ? dir.getFileName().toString()
                : fm.values().get("name").strip();
            String description = fm.values().getOrDefault("description", "").replace('\n', ' ').strip();
            return Optional.of(new Skill(name, description, dir, md, fm.values()));
        } catch (IOException | UncheckedIOException e) {
            log.warn("cannot read skill {}", md, e);
            return Optional.empty();
        }
    }

    /**
     * Reads the body of a skill (the text after the frontmatter).
     *
     * @param skill the skill
     * @return the body
     * @throws UncheckedIOException when the file cannot be read
     */
    public static String body(Skill skill) {
        try {
            return Frontmatter.parse(Files.readString(skill.skillMd(), StandardCharsets.UTF_8)).body();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + skill.skillMd(), e);
        }
    }

    /**
     * Returns the roots in precedence order.
     *
     * @return the roots
     */
    public List<Path> roots() {
        return roots;
    }
}
