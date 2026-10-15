/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The read-only command table of {@value #RESOURCE}: which {@code bash} command lines only read. A line qualifies when
 * {@link ShellLexer} finds only simple commands, no word is quoted or escaped (so that nothing can hide an option or a
 * path from the checks, here or in an approval extension that reads the raw text), each command starts with a listed
 * command and no word is a refused option.
 */
final class ReadOnlyRules {

    /** Class path resource holding the table, next to this class. */
    static final String RESOURCE = "read-only-commands.txt";
    private static final String LONG = "--";

    private final List<List<String>> commands;
    private final List<String> options;
    private final Map<String, String> letters;

    private ReadOnlyRules(List<List<String>> commands, List<String> options, Map<String, String> letters) {
        this.commands = List.copyOf(commands);
        this.options = List.copyOf(options);
        this.letters = Map.copyOf(letters);
    }

    /**
     * Loads the built-in table.
     *
     * @return the rules
     */
    static ReadOnlyRules builtin() {
        try (InputStream in = Objects.requireNonNull(ReadOnlyRules.class.getResourceAsStream(RESOURCE), RESOURCE)) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }

    static ReadOnlyRules parse(List<String> lines) {
        List<List<String>> commands = new ArrayList<>();
        List<String> options = new ArrayList<>();
        Map<String, String> letters = new HashMap<>();
        for (String line : lines) {
            List<String> f = List.of(line.strip().toLowerCase(Locale.ROOT).split("\\s+"));
            switch (f.get(0)) {
                case "command" -> commands.add(f.subList(1, f.size()));
                case "option" -> options.add(f.get(1));
                case "letters" -> letters.put(f.get(1), f.get(2));
                default -> {
                    // comments and blank lines
                }
            }
        }
        return new ReadOnlyRules(commands, options, letters);
    }

    /**
     * Tells whether a command line only reads.
     *
     * @param line the command line
     * @param cmdShell whether {@code cmd /c} runs it
     * @return true for a line of listed commands only
     */
    boolean readOnly(String line, boolean cmdShell) {
        ShellLexer.Result r = ShellLexer.lex(line, cmdShell);
        return r.plain() && r.commands().stream().allMatch(this::readOnly);
    }

    private boolean readOnly(List<ShellLexer.Word> command) {
        if (command.stream().anyMatch(ShellLexer.Word::quoted)) {
            return false;
        }
        List<String> words = command.stream().map(w -> w.text().toLowerCase(Locale.ROOT)).toList();
        boolean listed =
            commands.stream().anyMatch(c -> words.size() >= c.size() && words.subList(0, c.size()).equals(c));
        return listed && words.stream().noneMatch(w -> refused(words.get(0), w));
    }

    private boolean refused(String command, String word) {
        String name = word.contains("=") ? word.substring(0, word.indexOf('=')) : word;
        for (String o : options) {
            boolean abbreviated =
                name.startsWith(LONG) && name.length() > LONG.length() && o.startsWith(LONG) && o.startsWith(name);
            if (o.equals(name) || abbreviated) {
                return true;
            }
        }
        String refusedLetters = letters.get(command);
        if (refusedLetters == null || !word.startsWith("-") || word.startsWith(LONG)) {
            return false;
        }
        return word.chars().anyMatch(ch -> refusedLetters.indexOf(ch) >= 0);
    }
}
