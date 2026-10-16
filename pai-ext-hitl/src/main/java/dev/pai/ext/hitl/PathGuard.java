/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.RealPaths;
import dev.pai.core.tool.ShellCommands;
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
        Pattern.compile("(^|[\\s=])/|\\s-[A-Za-z]+/|~|\\.\\.|\\.[?*\\[]|\\b[A-Za-z]:|%");

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

    /** The working directory, absolute and normalized. */
    Path cwd() {
        return cwd;
    }

    /**
     * Checks a call, whatever the tool's effect.
     *
     * @param call the call
     * @return why the path is outside, empty when the call has no path or it is inside
     */
    Optional<String> check(ContentBlock.ToolCall call) {
        JsonNode path = call.arguments().path(PATH_ARGUMENT);
        return path.isTextual() ? outside(path.asText()) : Optional.empty();
    }

    /** Why a path is outside the working directory and the allowed directories, empty when it is inside. */
    private Optional<String> outside(String path) {
        Optional<Path> target;
        try {
            target = real(cwd.resolve(path).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return Optional.of("invalid path: " + path);
        }
        if (target.isEmpty()) {
            return Optional.of("path cannot be resolved safely (dangling link or I/O error): " + path);
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
     * Checks the words of a command that its tool reports as read-only, after {@link #commandMayLeaveCwd}: every word
     * after the program name, the value of {@code --x=value} and what is glued to a short option ({@code -fPATH}) is
     * resolved like a {@code path} argument (links followed; {@code -flnk} also as {@code lnk}); {@code -} (standard
     * input) and a line that is not plain also count. A word such as {@code HEAD:pom.xml} that is no valid path is
     * checked by its part after the colon (drive letters were caught before).
     *
     * @param command the command line
     * @return why the command may read outside, empty when every word stays inside
     */
    Optional<String> commandOutside(String command) {
        Optional<List<List<String>>> commands = ShellCommands.simpleCommands(command);
        if (commands.isEmpty()) {
            return Optional.of("the command is not plain words");
        }
        for (List<String> words : commands.get()) {
            for (String w : words.subList(1, words.size())) {
                if ("-".equals(w)) {
                    return Optional.of("the command reads standard input");
                }
                List<String> values = w.startsWith("--")
                    ? List.of(w.substring(w.indexOf('=') < 0 ? w.length() : w.indexOf('=') + 1)) : w.startsWith("-")
                        ? List.of(w.replaceFirst("^-[A-Za-z]*", ""), w.length() > 2 ? w.substring(2) : "") : List.of(w);
                for (String value : values) {
                    Optional<String> why = value.isEmpty() ? Optional.empty() : outside(value).filter(
                        o -> !value.contains(":") || outside(value.substring(value.lastIndexOf(':') + 1)).isPresent());
                    if (why.isPresent()) {
                        return why;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves symbolic links of the longest existing prefix and appends the rest ({@link RealPaths}, with the file
     * system root as the directory). Empty when that prefix cannot be resolved, for example because it is a dangling
     * link.
     */
    static Optional<Path> real(Path absolute) {
        Path a = absolute.toAbsolutePath();
        return RealPaths.inside(a.getRoot(), a.toString());
    }
}
