/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.model.ContentBlock;

/**
 * Keeps writes inside the working directory: the {@value #PATH_ARGUMENT} argument of a tool that is not read-only must
 * resolve, after following symbolic links, to the working directory or one of the allowed directories.
 */
final class PathGuard {

    /** Argument name the guard inspects. */
    static final String PATH_ARGUMENT = "path";

    private final Path cwd;
    private final List<Path> roots;

    /**
     * Creates the guard.
     *
     * @param cwd the working directory
     * @param allowed further directories writes may go to
     */
    PathGuard(Path cwd, List<Path> allowed) {
        this.cwd = cwd.toAbsolutePath().normalize();
        List<Path> r = new ArrayList<>();
        r.add(real(this.cwd));
        allowed.forEach(p -> r.add(real(p.toAbsolutePath().normalize())));
        this.roots = List.copyOf(r);
    }

    /**
     * Checks a call.
     *
     * @param call the call
     * @param tool the tool
     * @return why the call is refused, empty when it may proceed
     */
    Optional<String> check(ContentBlock.ToolCall call, Tool tool) {
        JsonNode path = call.arguments().path(PATH_ARGUMENT);
        if (tool.effect() == ToolEffectEnum.READ_ONLY || !path.isTextual()) {
            return Optional.empty();
        }
        Path target;
        try {
            target = real(cwd.resolve(path.asText()).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return Optional.of("invalid path: " + path.asText());
        }
        for (Path root : roots) {
            if (target.startsWith(root)) {
                return Optional.empty();
            }
        }
        return Optional.of("path outside the working directory: " + target);
    }

    /**
     * Resolves symbolic links of the longest existing prefix and appends the rest, so that a link inside the working
     * directory that points outside is seen for what it is.
     */
    static Path real(Path absolute) {
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        } catch (IOException e) {
            return absolute;
        }
    }
}
