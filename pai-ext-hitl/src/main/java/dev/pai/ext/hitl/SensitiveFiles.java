/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.pai.core.tool.ShellCommands;

/**
 * File names that look like secrets ({@code .env*}, {@code *.pem}, {@code id_*} ...), from the class path resource
 * {@value #RESOURCE} of pai-core: one glob per line, {@code #} lines are comments. A path matches when its file name
 * part does, in any directory, ignoring case. Read-only calls that name such a file are asked about instead of running
 * freely.
 */
final class SensitiveFiles {

    /** Class path resource holding the globs. */
    static final String RESOURCE = "/dev/pai/core/policy/sensitive-files.txt";

    private final Pattern names;

    private SensitiveFiles(List<String> globs) {
        this.names = Pattern.compile(String.join("|", globs.stream().map(SensitiveFiles::regex).toList()),
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** The built-in list. */
    static SensitiveFiles builtin() {
        try (InputStream in = Objects.requireNonNull(SensitiveFiles.class.getResourceAsStream(RESOURCE), RESOURCE)) {
            return of(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }

    /** A list from lines of the resource format. */
    static SensitiveFiles of(List<String> lines) {
        return new SensitiveFiles(
            lines.stream().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList());
    }

    /** Whether the file name part of a path (separators {@code /} and {@code \}) matches. */
    boolean matches(String path) {
        String p = path.strip();
        while (p.endsWith("/") || p.endsWith("\\")) {
            p = p.substring(0, p.length() - 1);
        }
        String name = p.substring(Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\')) + 1);
        return !name.isEmpty() && names.matcher(name).matches();
    }

    /**
     * The first word of a command line after its program name that names a sensitive file: the word itself, the value
     * after {@code =} and the part after the last {@code :} (as in {@code HEAD:.env}) are checked.
     *
     * @param command the command line
     * @return the word, empty when none does or the line is not plain words
     */
    Optional<String> inCommand(String command) {
        for (List<String> words : ShellCommands.simpleCommands(command).orElse(List.of())) {
            for (String w : words.subList(Math.min(1, words.size()), words.size())) {
                if (matches(w) || matches(w.substring(w.indexOf('=') + 1))
                    || matches(w.substring(w.lastIndexOf(':') + 1))) {
                    return Optional.of(w);
                }
            }
        }
        return Optional.empty();
    }

    private static String regex(String glob) {
        StringBuilder b = new StringBuilder("(?:");
        for (String part : glob.split("(?=[*?])|(?<=[*?])")) {
            b.append("*".equals(part) ? ".*" : "?".equals(part) ? "." : Pattern.quote(part));
        }
        return b.append(')').toString();
    }
}
