/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a command line into simple commands and words the way {@code bash} would, without expanding anything. It knows
 * quotes ({@code '...'}, {@code "..."}), backslash escapes, the separators {@code ;} {@code &&} {@code ||} {@code |}
 * {@code &} and newline, redirections ({@code <} {@code >} and their variants) and substitutions ({@code $...},
 * {@code `...`}, {@code <(...)}, {@code >(...)}). Anything it does not model (groups and subshells, comments, control
 * characters, unterminated quotes, empty commands) is reported as unsupported; for the {@code cmd} shell also its
 * quote, escape and variable characters. Callers decide fail-closed: only a {@link Result#plain()} result may be
 * trusted.
 */
final class ShellLexer {

    private static final char DEL = 0x7f;

    private final String line;
    private final boolean cmdShell;
    private final List<List<Word>> commands = new ArrayList<>();
    private List<Word> words = new ArrayList<>();
    private final StringBuilder word = new StringBuilder();
    private boolean inWord;
    private boolean quoted;
    private boolean redirect;
    private boolean substitution;
    private boolean unsupported;
    private int i;

    private ShellLexer(String line, boolean cmdShell) {
        this.line = line;
        this.cmdShell = cmdShell;
    }

    /**
     * One word of a simple command, without its quotes and escapes.
     *
     * @param text the word as the command receives it
     * @param quoted whether any part of it was quoted or escaped
     */
    record Word(String text, boolean quoted) {
    }

    /**
     * What a command line consists of.
     *
     * @param commands the simple commands, each a non-empty list of words
     * @param redirect whether any redirection appears
     * @param substitution whether any expansion or command substitution appears
     * @param unsupported whether any construct the lexer does not model appears
     */
    record Result(List<List<Word>> commands, boolean redirect, boolean substitution, boolean unsupported) {

        /** Whether the line is only simple commands joined by separators, so that the words say everything. */
        boolean plain() {
            return !redirect && !substitution && !unsupported && !commands.isEmpty();
        }
    }

    /**
     * Lexes a command line.
     *
     * @param line the command line
     * @param cmdShell whether {@code cmd /c} runs it instead of {@code bash}
     * @return the commands and what else was found
     */
    static Result lex(String line, boolean cmdShell) {
        ShellLexer l = new ShellLexer(line.strip(), cmdShell);
        l.run();
        return new Result(List.copyOf(l.commands), l.redirect, l.substitution, l.unsupported);
    }

    private void run() {
        while (i < line.length()) {
            char c = line.charAt(i++);
            if (c == ' ' || c == '\t') {
                endWord();
            } else if (c == '\n' || c == ';') {
                endCommand();
            } else if (c == '&' || c == '|') {
                operator(c);
            } else if (c == '<' || c == '>') {
                redirect = true;
                substitution |= peek('(');
                endWord();
            } else if (c == '$' || c == '`') {
                substitution = true;
            } else if (cmdShell && "\"'^%!".indexOf(c) >= 0 || c < ' ' || c == DEL || "(){}".indexOf(c) >= 0
                || c == '#' && !inWord) {
                unsupported = true;
            } else if (c == '\'') {
                singleQuoted();
            } else if (c == '"') {
                doubleQuoted();
            } else if (c == '\\') {
                escaped();
            } else {
                append(c);
            }
        }
        endCommand();
    }

    private void operator(char c) {
        if (c == '&' && peek('>') || c == '|' && peek('&')) {
            redirect = true;
        }
        if (peek(c) || peek('&') || peek('>')) {
            i++;
        }
        endCommand();
    }

    private void singleQuoted() {
        int close = line.indexOf('\'', i);
        if (close < 0) {
            unsupported = true;
            i = line.length();
            return;
        }
        appendQuoted(line.substring(i, close));
        i = close + 1;
    }

    private void doubleQuoted() {
        StringBuilder sb = new StringBuilder();
        while (i < line.length() && line.charAt(i) != '"') {
            char c = line.charAt(i++);
            if (c == '$' || c == '`') {
                substitution = true;
            } else if (c == '\\' && i < line.length() && "$`\"\\\n".indexOf(line.charAt(i)) >= 0) {
                sb.append(line.charAt(i++));
            } else {
                sb.append(c);
            }
        }
        if (i >= line.length()) {
            unsupported = true;
        }
        i++;
        appendQuoted(sb.toString());
    }

    private void escaped() {
        if (cmdShell) {
            appendQuoted("\\");
        } else if (i < line.length()) {
            appendQuoted(String.valueOf(line.charAt(i++)));
        } else {
            unsupported = true;
        }
    }

    private boolean peek(char c) {
        return i < line.length() && line.charAt(i) == c;
    }

    private void append(char c) {
        word.append(c);
        inWord = true;
    }

    private void appendQuoted(String s) {
        word.append(s);
        inWord = true;
        quoted = true;
    }

    private void endWord() {
        if (inWord) {
            words.add(new Word(word.toString(), quoted));
        }
        word.setLength(0);
        inWord = false;
        quoted = false;
    }

    private void endCommand() {
        endWord();
        if (words.isEmpty()) {
            unsupported |= i < line.length() || !commands.isEmpty() || !line.isEmpty();
        } else {
            commands.add(List.copyOf(words));
        }
        words = new ArrayList<>();
    }
}
