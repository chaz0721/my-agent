/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * A choice box for a question with fixed answers: a border with the question as title, one row per answer (its key and
 * what it means), arrows to move, Enter to confirm, a digit or an answer key to choose directly, Esc or Ctrl+C to
 * cancel (no answer, which the asking extensions treat as no). The meanings come from the question's last line when it
 * spells them out as {@code k = meaning, ...} (the approval and memory questions do); the rest of the question is the
 * body, printed above the box.
 */
final class Picker {

    /** Keys read while picking: Esc, Ctrl+C, Ctrl+D, Enter. */
    static final int ESC = 27;
    static final int CTRL_C = 3;
    static final int CTRL_D = 4;
    /** Returned by a key read that timed out. */
    static final int TIMEOUT = -2;
    private static final Pattern PAIR = Pattern.compile("(?:\\(|^|, |\\? )(\\S{1,3}) = ");
    /** How long to wait after Esc for the rest of an arrow key sequence. */
    private static final long ESC_WAIT_MILLIS = 80L;
    private static final String HINT = " up/down, Enter, Esc ";

    /**
     * One answer.
     *
     * @param key what {@code ask} returns for it
     * @param label what it means
     */
    record Option(String key, String label) {
    }

    private final String title;
    private final String body;
    private final List<Option> options;
    private int selected;

    private Picker(String title, String body, List<Option> options) {
        this.title = title;
        this.body = body;
        this.options = options;
    }

    /**
     * Builds the box for a question.
     *
     * @param prompt the question, control characters already made visible
     * @param choices the answers
     * @return the picker
     */
    static Picker of(String prompt, List<String> choices) {
        List<String> lines = prompt.stripTrailing().lines().toList();
        String last = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
        Matcher m = PAIR.matcher(last);
        List<int[]> spans = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        while (m.find()) {
            if (choices.contains(m.group(1)) && !keys.contains(m.group(1))) {
                spans.add(new int[] {m.start(), m.end()});
                keys.add(m.group(1));
            }
        }
        List<Option> options = new ArrayList<>();
        if (keys.isEmpty()) {
            choices.forEach(c -> options.add(new Option(c, defaultLabel(c))));
            return new Picker("choose", prompt.stripTrailing(), options);
        }
        for (String c : choices) {
            int k = keys.indexOf(c);
            String label = defaultLabel(c);
            if (k >= 0) {
                int end = k + 1 < spans.size() ? spans.get(k + 1)[0] : last.length();
                label = last.substring(spans.get(k)[1], end).strip();
                label = label.endsWith(")") && !label.contains("(") ? label.substring(0, label.length() - 1) : label;
            }
            options.add(new Option(c, label));
        }
        int at = spans.get(0)[0];
        String head = (last.substring(0, at) + (last.startsWith("?", at) ? "?" : "")).strip();
        head = head.endsWith("(") ? head.substring(0, head.length() - 1).strip() : head;
        String rest = String.join("\n", lines.subList(0, lines.size() - 1));
        return new Picker(head.isEmpty() ? "choose" : head, rest, options);
    }

    private static String defaultLabel(String choice) {
        return switch (choice) {
            case "y" -> "yes";
            case "n" -> "no";
            default -> choice;
        };
    }

    String title() {
        return title;
    }

    /** The question without its answer line; printed above the box. */
    String body() {
        return body;
    }

    List<Option> options() {
        return options;
    }

    /**
     * Describes how the box ended, for the line that replaces it.
     *
     * @param chosen whether an answer was chosen (else the box was cancelled)
     * @param answer the answer returned
     * @return for example {@code y (yes)} or {@code cancelled n (no)}
     */
    String outcome(boolean chosen, String answer) {
        String shown = options.stream().filter(o -> o.key().equals(answer)).findFirst()
            .map(o -> o.key() + " (" + o.label() + ")").orElse("");
        return chosen ? shown : ("cancelled " + shown).strip();
    }

    /**
     * Draws the box.
     *
     * @param t the colours
     * @param s the glyphs
     * @param width terminal columns
     * @return the lines
     */
    List<AttributedString> lines(Theme t, Symbols s, int width) {
        int inner = Math.max(Cols.width(title) + 4, Cols.width(HINT) + 2);
        for (Option o : options) {
            inner = Math.max(inner, Cols.width(o.key()) + Cols.width(o.label()) + 6);
        }
        inner = Math.max(4, Math.min(inner, width - 2));
        String[] c = s.corners();
        String rule = s.rule();
        List<AttributedString> out = new ArrayList<>();
        out.add(border(c[0], " " + title + " ", c[1], inner, rule, t, true));
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            boolean on = i == selected;
            String row = Cols.cut((on ? s.pointer() : " ") + " " + o.key() + "  " + o.label(), inner - 2);
            out.add(new AttributedStringBuilder().append(s.bar() + " ", t.faint())
                .append(Cols.pad(row, inner - 2), on ? t.tool().bold() : AttributedStyle.DEFAULT)
                .append(" " + s.bar(), t.faint()).toAttributedString());
        }
        out.add(border(c[2], HINT, c[3], inner, rule, t, false));
        return out;
    }

    private static AttributedString border(String left, String text, String right, int inner, String rule, Theme t,
        boolean title) {
        String label = Cols.cut(text, Math.max(0, inner - 2));
        String fill = rule.repeat(Math.max(0, inner - 1 - Cols.width(label)));
        return new AttributedStringBuilder().append(left + rule, t.faint())
            .append(label, title ? t.emphasis() : t.faint()).append(fill + right, t.faint()).toAttributedString();
    }

    /**
     * Source of keys while picking.
     */
    interface Keys {

        /**
         * Reads one character.
         *
         * @param timeoutMillis how long to wait; 0 waits until one arrives
         * @return the character, -1 at end of input, {@value Picker#TIMEOUT} on timeout
         * @throws IOException on read failure
         */
        int read(long timeoutMillis) throws IOException;
    }

    /**
     * Handles keys until an answer is chosen or the box is cancelled.
     *
     * @param keys the key source
     * @param redraw called after the selection moved
     * @return the chosen key; empty when cancelled
     * @throws IOException on read failure
     */
    Optional<String> run(Keys keys, Runnable redraw) throws IOException {
        while (true) {
            int ch = keys.read(0);
            if (ch < 0 || ch == CTRL_C || ch == CTRL_D) {
                return Optional.empty();
            }
            if (ch == ESC) {
                int next = keys.read(ESC_WAIT_MILLIS);
                if (next == TIMEOUT || next == ESC || next < 0) {
                    return Optional.empty();
                }
                int code = next == '[' || next == 'O' ? keys.read(ESC_WAIT_MILLIS) : next;
                move(code == 'A' ? -1 : code == 'B' ? 1 : 0);
                redraw.run();
                continue;
            }
            if (ch == '\r' || ch == '\n') {
                return Optional.of(options.get(selected).key());
            }
            Optional<String> direct = direct(ch);
            if (direct.isPresent()) {
                return direct;
            }
        }
    }

    /** An answer key, exactly or ignoring case, or the 1-based number of an answer. */
    private Optional<String> direct(int ch) {
        String typed = String.valueOf((char)ch);
        for (Option o : options) {
            if (o.key().equals(typed)) {
                return Optional.of(o.key());
            }
        }
        for (Option o : options) {
            if (o.key().toLowerCase(Locale.ROOT).equals(typed.toLowerCase(Locale.ROOT))) {
                return Optional.of(o.key());
            }
        }
        if (ch >= '1' && ch <= '9' && ch - '1' < options.size()) {
            return Optional.of(options.get(ch - '1').key());
        }
        return Optional.empty();
    }

    private void move(int by) {
        selected = Math.floorMod(selected + by, options.size());
    }
}
