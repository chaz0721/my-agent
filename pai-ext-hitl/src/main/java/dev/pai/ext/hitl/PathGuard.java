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
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.ContentBlock;

/**
 * Checks the {@value #PATH_ARGUMENT} argument of a tool call (and only that argument; commands run by {@code bash} are
 * covered by approval and {@link CommandGuard}): after following symbolic links it must lie in the working directory or
 * an allowed directory. Fails closed: a path that cannot be resolved (a dangling link, an I/O error, an invalid name)
 * counts as outside. The caller decides what outside means for the tool's effect.
 */
final class PathGuard {

    /** Argument name the guard inspects. */
    static final String PATH_ARGUMENT = "path";

    private static final Pattern OUTSIDE =
        Pattern.compile("(^|[\\s=])/|\\s-[A-Za-z]/|~|\\.\\.|\\.[?*\\[]|\\b[A-Za-z]:|%");

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
        r.add(real(this.cwd).orElse(this.cwd));
        allowed.forEach(p -> real(p.toAbsolutePath().normalize()).ifPresent(r::add));
        this.roots = List.copyOf(r);
    }

    /**
     * Checks a call, whatever the tool's effect.
     *
     * @param call the call
     * @return why the path is outside, empty when the call has no path or it is inside
     */
    Optional<String> check(ContentBlock.ToolCall call) {
        JsonNode path = call.arguments().path(PATH_ARGUMENT);
        if (!path.isTextual()) {
            return Optional.empty();
        }
        Optional<Path> target;
        try {
            target = real(cwd.resolve(path.asText()).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return Optional.of("invalid path: " + path.asText());
        }
        if (target.isEmpty()) {
            return Optional.of("path cannot be resolved safely (dangling link or I/O error): " + path.asText());
        }
        for (Path root : roots) {
            if (target.get().startsWith(root)) {
                return Optional.empty();
            }
        }
        return Optional.of("path outside the working directory: " + target.get());
    }

    /**
     * Tells whether a command that its tool reports as read-only may still read outside the working directory: an
     * absolute path (also after {@code =} or glued to a short option such as {@code -f/etc/x}), {@code ~}, {@code ..},
     * a glob after a dot ({@code .?}, {@code .*}, {@code .[}), a drive letter or a {@code %} variable appears in it.
     * The text is checked as written; this is sound because the shell tool reports no line with quotes, escapes or
     * substitutions as read-only.
     *
     * @param command the command line
     * @return true when the user should be asked
     */
    static boolean commandMayLeaveCwd(String command) {
        return OUTSIDE.matcher(command).find();
    }

    /**
     * Resolves symbolic links of the longest existing prefix (looked up without following links) and appends the rest.
     * Empty when that prefix cannot be resolved, for example because it is a dangling link.
     */
    static Optional<Path> real(Path absolute) {
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return Optional.of(absolute);
        }
        try {
            return Optional.of(existing.toRealPath().resolve(existing.relativize(absolute)).normalize());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
