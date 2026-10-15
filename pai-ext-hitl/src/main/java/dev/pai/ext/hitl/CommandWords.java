/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import dev.pai.core.tool.ShellCommands;

/**
 * What a {@code bash} command line runs, on the words {@link ShellCommands#simpleCommands} splits it into: wrappers
 * ({@code env}, {@code command}, {@code nice} ...), their options, variable assignments and shell keywords are dropped,
 * and the program name loses its directory and {@code .exe} ({@code /usr/bin/git} is {@code git}). A line is opaque
 * when that cannot be told: it is not plain, or it hands a command line to an interpreter ({@code sh -c}, {@code eval},
 * {@code xargs}, {@code python} ...).
 */
final class CommandWords {

    private static final Set<String> WRAPPERS = Set.of("env", "command", "builtin", "time", "nice", "nohup", "exec",
        "sudo", "doas", "timeout", "stdbuf", "watch", "ionice", "chrt");
    private static final Set<String> KEYWORDS = Set.of("if", "then", "else", "elif", "fi", "do", "done", "while",
        "until", "for", "case", "esac", "select", "function", "!");
    private static final Set<String> INTERPRETERS = Set.of("sh", "bash", "zsh", "dash", "ksh", "fish", "pwsh",
        "powershell", "cmd", "eval", "source", ".", "xargs", "node", "npx", "perl", "ruby", "busybox");
    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");
    private static final Pattern WRAPPER_ARGUMENT =
        Pattern.compile("-.*|[0-9][0-9.]*[smhd]?|[A-Za-z_][A-Za-z0-9_]*=.*");
    private static final String EXE = ".exe";

    private CommandWords() {}

    /** The program a word names: without directory and {@code .exe}, in lower case. */
    static String program(String word) {
        String w = word.substring(Math.max(word.lastIndexOf('/'), word.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        return w.endsWith(EXE) ? w.substring(0, w.length() - EXE.length()) : w;
    }

    /** The words of one simple command from the program on, the program name normalized; empty when nothing runs. */
    static List<String> effective(List<String> words) {
        int i = 0;
        while (i < words.size()) {
            String w = words.get(i);
            if (ASSIGNMENT.matcher(w).matches() || KEYWORDS.contains(w)) {
                i++;
            } else if (WRAPPERS.contains(program(w))) {
                i++;
                while (i < words.size() && WRAPPER_ARGUMENT.matcher(words.get(i)).matches()) {
                    i++;
                }
            } else {
                break;
            }
        }
        if (i >= words.size()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(words.subList(i, words.size()));
        out.set(0, program(out.get(0)));
        return List.copyOf(out);
    }

    /**
     * The effective words of every simple command, empty when the line is not plain. On Windows the shell tool may fall
     * back to {@code cmd}, which drops {@code ^} and expands {@code %x%} and {@code !x!}, so those make a line unclean
     * there.
     */
    static Optional<List<List<String>>> commands(String line, boolean windows) {
        return ShellCommands.simpleCommands(line, windows).map(cs -> cs.stream().map(CommandWords::effective).toList());
    }

    /** Whether it cannot be told what the line runs. */
    static boolean opaque(String line, boolean windows) {
        Optional<List<List<String>>> raw = ShellCommands.simpleCommands(line, windows);
        return raw.isEmpty() || raw.get().stream().anyMatch(c -> KEYWORDS.contains(c.get(0)))
            || commands(line, windows).orElseThrow().stream().anyMatch(c -> c.isEmpty() || interpreter(c.get(0)));
    }

    /** Whether any simple command of the line runs {@code program}. */
    static boolean runs(String line, String program, boolean windows) {
        return commands(line, windows).map(cs -> cs.stream().anyMatch(c -> !c.isEmpty() && program.equals(c.get(0))))
            .orElse(false);
    }

    /** Whether a first word should not become an allow rule: an assignment, a wrapper, keyword or interpreter. */
    static boolean unfitForRule(String first) {
        String p = program(first);
        return ASSIGNMENT.matcher(first).matches() || WRAPPERS.contains(p) || KEYWORDS.contains(p) || interpreter(p);
    }

    private static boolean interpreter(String program) {
        return INTERPRETERS.contains(program) || program.startsWith("python");
    }
}
