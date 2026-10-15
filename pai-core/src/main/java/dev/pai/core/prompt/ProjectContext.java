/* pai-next — Apache-2.0 */
package dev.pai.core.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Project memory files ({@code PAI.md}) injected into the system prompt as a {@code <project-context>} section. Files
 * are read in order {@code ~/.pai/PAI.md}, {@code <cwd>/PAI.md}, {@code <cwd>/.pai/PAI.md}, {@code <cwd>/PAI.local.md};
 * a line {@code @relative/path.md} is replaced by that file (relative to the including file, nested up to
 * {@link #MAX_IMPORT_DEPTH}, each file at most once). The result is cut at {@link #MAX_CHARS}.
 */
public final class ProjectContext {

    /** Total character budget of the section. */
    public static final int MAX_CHARS = 8_000;
    /** Deepest nesting of {@code @} imports. */
    public static final int MAX_IMPORT_DEPTH = 3;
    /** Section heading used in the system prompt. */
    public static final String SECTION = "Project context";
    /** Name of the project memory file. */
    public static final String FILE_NAME = "PAI.md";

    private static final Logger log = LoggerFactory.getLogger(ProjectContext.class);
    private static final Pattern IMPORT = Pattern.compile("^@(\\S+\\.md)\\s*$");

    private ProjectContext() {}

    /**
     * Returns the files consulted, in load order, whether or not they exist.
     *
     * @param paiHome the {@code ~/.pai} directory
     * @param cwd the working directory
     * @return candidate paths
     */
    public static List<Path> candidates(Path paiHome, Path cwd) {
        return List.of(paiHome.resolve(FILE_NAME), cwd.resolve(FILE_NAME), cwd.resolve(".pai").resolve(FILE_NAME),
            cwd.resolve("PAI.local.md"));
    }

    /**
     * Loads and concatenates the project memory files of a trusted project.
     *
     * @param paiHome the {@code ~/.pai} directory
     * @param cwd the working directory
     * @return the section body wrapped in {@code <project-context>}, empty when no file exists
     */
    public static Optional<String> load(Path paiHome, Path cwd) {
        return load(paiHome, cwd, true);
    }

    /**
     * Loads and concatenates the project memory files. In a project that is not trusted, files under {@code cwd} may
     * only import by relative paths without {@code ..}, and both the files and their import targets must resolve
     * (symbolic links followed) inside the real {@code cwd}: a file that does not is skipped, an import that does not
     * stays as a plain line. The file in {@code paiHome} belongs to the user and is never restricted.
     *
     * @param paiHome the {@code ~/.pai} directory
     * @param cwd the working directory
     * @param trusted whether the user trusts {@code cwd}
     * @return the section body wrapped in {@code <project-context>}, empty when no file exists
     */
    public static Optional<String> load(Path paiHome, Path cwd, boolean trusted) {
        StringBuilder sb = new StringBuilder();
        Set<Path> seen = new HashSet<>();
        Path userFile = paiHome.resolve(FILE_NAME);
        Optional<Path> realCwd = trusted ? Optional.empty() : realPath(cwd);
        for (Path file : candidates(paiHome, cwd)) {
            boolean restricted = !trusted && !file.equals(userFile);
            if (Files.isRegularFile(file) && !(restricted && !inside(file, realCwd))) {
                Optional<Path> confine = restricted ? realCwd : Optional.empty();
                String text = expand(file, 0, seen, confine).strip();
                if (!text.isEmpty()) {
                    sb.append(sb.isEmpty() ? "" : "\n\n").append("<!-- ").append(file).append(" -->\n").append(text);
                }
            }
        }
        if (sb.isEmpty()) {
            return Optional.empty();
        }
        String body = sb.toString();
        if (body.length() > MAX_CHARS) {
            body = body.substring(0, MAX_CHARS) + "\n[project context truncated at " + MAX_CHARS + " characters]";
        }
        return Optional.of("<project-context>\n" + body + "\n</project-context>");
    }

    /** Expands imports; with {@code confine} present, only relative imports resolving inside it are followed. */
    private static String expand(Path file, int depth, Set<Path> seen, Optional<Path> confine) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!seen.add(normalized)) {
            return "";
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(normalized, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("cannot read project context file {}", normalized, e);
            return "";
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            Matcher m = IMPORT.matcher(line.strip());
            boolean allowed = m.matches() && !(confine.isPresent() && escapes(m.group(1)));
            Path target = allowed ? normalized.getParent().resolve(m.group(1)).normalize() : null;
            boolean confined = target == null || confine.isEmpty() || inside(target, confine);
            if (target != null && confined && depth < MAX_IMPORT_DEPTH && Files.isRegularFile(target)) {
                out.add(expand(target, depth + 1, seen, confine).strip());
            } else {
                out.add(line);
            }
        }
        return String.join("\n", out);
    }

    private static Optional<Path> realPath(Path p) {
        try {
            return Optional.of(p.toRealPath());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Whether a file resolves (links followed) inside {@code root}; false when either cannot be resolved. */
    private static boolean inside(Path file, Optional<Path> root) {
        return root.isPresent() && realPath(file).map(r -> r.startsWith(root.get())).orElse(false);
    }

    /** Whether an import path has a root or drive (absolute or drive-relative) or climbs with {@code ..}. */
    private static boolean escapes(String importPath) {
        if (importPath.startsWith("/") || importPath.startsWith("\\") || importPath.indexOf(':') >= 0) {
            return true;
        }
        for (String part : importPath.split("[/\\\\]")) {
            if ("..".equals(part)) {
                return true;
            }
        }
        return false;
    }
}
