/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Splits a shell command line into simple commands and their words, but only when the line is plain: nothing but words
 * separated by blanks, joined by {@code ;} {@code &&} {@code ||} {@code |} {@code &} or newlines. Anything the words
 * would not show as the program receives them makes the line unclean and the result empty (fail-closed): quotes and
 * escapes, {@code $} and backticks, redirections, globs ({@code * ? [}, which the shell expands into words such as
 * {@code --pre=sh}), groups and subshells, {@code #}, control characters, empty commands; for {@code cmd} also
 * {@code % ^ !}. Used by the shell tool to tell read-only lines and by approval policies to match rules.
 */
public final class ShellCommands {

    private static final String UNCLEAN = "'\"\\$`<>*?[](){}#";
    private static final String CMD_UNCLEAN = "%^!";
    private static final char DEL = 0x7f;

    private ShellCommands() {}

    /**
     * Returns the simple commands of a {@code bash} line.
     *
     * @param line the command line
     * @return the commands, each a non-empty list of words; empty when the line is not plain
     */
    public static Optional<List<List<String>>> simpleCommands(String line) {
        return simpleCommands(line, false);
    }

    /**
     * Returns the simple commands of a line run by {@code bash} or, when {@code cmdShell}, by {@code cmd /c}.
     *
     * @param line the command line
     * @param cmdShell whether {@code cmd} runs it
     * @return the commands, each a non-empty list of words; empty when the line is not plain
     */
    public static Optional<List<List<String>>> simpleCommands(String line, boolean cmdShell) {
        String s = line.strip();
        List<List<String>> commands = new ArrayList<>();
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean separator = c == ';' || c == '\n' || c == '&' || c == '|';
            if (c == '|' && i + 1 < s.length() && s.charAt(i + 1) == '&' || UNCLEAN.indexOf(c) >= 0
                || cmdShell && CMD_UNCLEAN.indexOf(c) >= 0 || c < ' ' && c != '\t' && c != '\n' || c == DEL) {
                return Optional.empty();
            }
            if (c == ' ' || c == '\t' || separator) {
                if (!word.isEmpty()) {
                    words.add(word.toString());
                    word.setLength(0);
                }
            } else {
                word.append(c);
            }
            if (separator) {
                boolean doubled = (c == '&' || c == '|') && i + 1 < s.length() && s.charAt(i + 1) == c;
                i += doubled ? 1 : 0;
                if (words.isEmpty()) {
                    return Optional.empty();
                }
                commands.add(List.copyOf(words));
                words.clear();
            }
        }
        if (!word.isEmpty()) {
            words.add(word.toString());
        }
        if (words.isEmpty()) {
            return Optional.empty();
        }
        commands.add(List.copyOf(words));
        return Optional.of(List.copyOf(commands));
    }
}
