/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.pai.core.tool.ShellCommands;

/**
 * File names that look like secrets ({@code .env*}, {@code *.pem}, {@code id_rsa*} ...), from the class path resource
 * {@value #RESOURCE} of pai-core: one glob per line, {@code #} lines are comments, a line starting with {@code !}
 * excludes names ({@code !.env.example}). A path matches when its file name part does, in any directory, ignoring case
 * and trailing dots and spaces (Windows opens {@code x.pem.} as {@code x.pem}), or when the name of the file it really
 * is (links followed) does. Read-only calls that name such a file are asked about instead of running freely. Without
 * the resource a minimal built-in list applies (fail closed).
 */
final class SensitiveFiles {

    /** Class path resource holding the globs. */
    static final String RESOURCE = "/dev/pai/core/policy/sensitive-files.txt";
    /** Used when the resource cannot be read. */
    static final List<String> FALLBACK = List.of(".env*", "*.pem", "*.key", "id_rsa*");

    private static final Logger log = LoggerFactory.getLogger(SensitiveFiles.class);
    private static final String EXCLUDE = "!";

    private final Pattern names;
    private final Pattern excluded;

    private SensitiveFiles(List<String> globs, List<String> exclusions) {
        this.names = compile(globs);
        this.excluded = compile(exclusions);
    }

    /** The built-in list, or {@link #FALLBACK} with a warning when it cannot be read. */
    static SensitiveFiles builtin() {
        return builtin(SensitiveFiles.class.getResourceAsStream(RESOURCE));
    }

    static SensitiveFiles builtin(InputStream resource) {
        if (resource == null) {
            log.warn("{} not found; using the minimal sensitive-file list {}", RESOURCE, FALLBACK);
            return of(FALLBACK);
        }
        try (InputStream in = resource) {
            return of(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList());
        } catch (IOException | UncheckedIOException e) {
            log.warn("cannot read {}; using the minimal sensitive-file list {}", RESOURCE, FALLBACK, e);
            return of(FALLBACK);
        }
    }

    /** A list from lines of the resource format. */
    static SensitiveFiles of(List<String> lines) {
        List<String> kept = lines.stream().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        return new SensitiveFiles(kept.stream().filter(l -> !l.startsWith(EXCLUDE)).toList(),
            kept.stream().filter(l -> l.startsWith(EXCLUDE)).map(l -> l.substring(1)).toList());
    }

    /** Whether the file name part of a path (separators {@code /} and {@code \}) matches. */
    boolean matches(String path) {
        String p = path.strip();
        while (p.endsWith("/") || p.endsWith("\\")) {
            p = p.substring(0, p.length() - 1);
        }
        String name = p.substring(Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\')) + 1).replaceAll("[. ]+$", "");
        return !name.isEmpty() && names.matcher(name).matches() && !excluded.matcher(name).matches();
    }

    /**
     * Whether a path matches by its written name or, when it exists, by the name of the file it really is.
     *
     * @param path the path as written
     * @param cwd the directory a relative path is resolved against
     * @return true when either name matches
     */
    boolean matches(String path, Path cwd) {
        if (matches(path)) {
            return true;
        }
        if (path.isBlank()) {
            return false;
        }
        try {
            Path real = cwd.resolve(path).toRealPath();
            return real.getFileName() != null && matches(real.getFileName().toString());
        } catch (IOException | InvalidPathException | SecurityException e) {
            return false;
        }
    }

    /**
     * The first word of a command line after its program name that names a sensitive file: the word itself, the value
     * after {@code =} and the part after the last {@code :} (as in {@code HEAD:.env}) are checked, each also by its
     * real name.
     *
     * @param command the command line
     * @param cwd the directory relative words are resolved against
     * @return the word, empty when none does or the line is not plain words
     */
    Optional<String> inCommand(String command, Path cwd) {
        for (List<String> words : ShellCommands.simpleCommands(command).orElse(List.of())) {
            for (String w : words.subList(Math.min(1, words.size()), words.size())) {
                if (matches(w, cwd) || matches(w.substring(w.indexOf('=') + 1), cwd)
                    || matches(w.substring(w.lastIndexOf(':') + 1), cwd)) {
                    return Optional.of(w);
                }
            }
        }
        return Optional.empty();
    }

    private static Pattern compile(List<String> globs) {
        return globs.isEmpty() ? Pattern.compile("(?!)")
            : Pattern.compile(String.join("|", globs.stream().map(SensitiveFiles::regex).toList()),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static String regex(String glob) {
        StringBuilder b = new StringBuilder("(?:");
        for (String part : glob.split("(?=[*?])|(?<=[*?])")) {
            b.append("*".equals(part) ? ".*" : "?".equals(part) ? "." : Pattern.quote(part));
        }
        return b.append(')').toString();
    }
}
