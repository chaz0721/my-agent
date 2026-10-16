/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * Coloured line diffs: added lines green, removed red, context faint, each with its line number. One renderer
 * ({@link #render}) serves both sources: the plain unified diff that the approval extension puts in its question
 * ({@link #parseUnified}) and the old and new text of a finished {@code edit} call ({@link #edit}).
 */
final class DiffView {

    /** Removed line. */
    static final char REMOVED = '-';
    /** Added line. */
    static final char ADDED = '+';
    /** Context line. */
    static final char CONTEXT = ' ';
    /** File or hunk header, or a note; shown without a number. */
    static final char HEADER = 'h';
    /** Larger texts are shown as all removed, then all added. */
    static final long MAX_CELLS = 250_000L;

    private static final Pattern HUNK = Pattern.compile("@@ -(\\d+)(,\\d+)? \\+(\\d+)(,\\d+)? @@.*");

    private DiffView() {}

    /**
     * One diff line.
     *
     * @param kind {@link #REMOVED}, {@link #ADDED}, {@link #CONTEXT} or {@link #HEADER}
     * @param number line number in the old file (removed) or the new one (others); 0 when unknown
     * @param text the line without its marker
     */
    record Line(char kind, int number, String text) {
    }

    /**
     * Returns whether a text contains a unified diff: a {@code --- } line and an added line (the hitl preview also
     * shows {@code --- path} with bare {@code -/+} lines when the edit will fail).
     *
     * @param text the text
     * @return true when it does
     */
    static boolean containsDiff(String text) {
        List<String> lines = text.lines().toList();
        return lines.stream().anyMatch(l -> l.startsWith("--- ")) && lines.stream().anyMatch(l -> l.startsWith("+"));
    }

    /**
     * Reads a unified diff; line numbers follow the {@code @@} headers.
     *
     * @param lines the diff lines, from the first {@code ---} on
     * @return the lines
     */
    static List<Line> parseUnified(List<String> lines) {
        List<Line> out = new ArrayList<>();
        int oldNo = 0;
        int newNo = 0;
        for (String l : lines) {
            Matcher m = HUNK.matcher(l);
            if (m.matches()) {
                oldNo = Integer.parseInt(m.group(1));
                newNo = Integer.parseInt(m.group(3));
                out.add(new Line(HEADER, 0, l));
            } else if (l.startsWith("--- ") || l.startsWith("+++ ")) {
                out.add(new Line(HEADER, 0, l));
            } else if (l.startsWith("-")) {
                out.add(new Line(REMOVED, oldNo > 0 ? oldNo++ : 0, l.substring(1)));
            } else if (l.startsWith("+")) {
                out.add(new Line(ADDED, newNo > 0 ? newNo++ : 0, l.substring(1)));
            } else if (l.startsWith(" ")) {
                out.add(new Line(CONTEXT, newNo > 0 ? newNo : 0, l.substring(1)));
                oldNo = oldNo > 0 ? oldNo + 1 : 0;
                newNo = newNo > 0 ? newNo + 1 : 0;
            } else {
                out.add(new Line(HEADER, 0, l));
            }
        }
        return out;
    }

    /**
     * Diffs the replaced and the replacing text of an edit.
     *
     * @param oldText the text replaced
     * @param newText the text put in
     * @param firstLine number of the first line of the region in the file; 0 when unknown
     * @return the lines
     */
    static List<Line> edit(String oldText, String newText, int firstLine) {
        List<String> a = oldText.lines().toList();
        List<String> b = newText.lines().toList();
        int pre = 0;
        while (pre < a.size() && pre < b.size() && a.get(pre).equals(b.get(pre))) {
            pre++;
        }
        int suf = 0;
        while (suf < a.size() - pre && suf < b.size() - pre
            && a.get(a.size() - 1 - suf).equals(b.get(b.size() - 1 - suf))) {
            suf++;
        }
        boolean numbered = firstLine > 0;
        int[] no = {firstLine, firstLine};
        List<Line> out = new ArrayList<>();
        a.subList(0, pre).forEach(l -> out.add(context(l, no, numbered)));
        middle(a.subList(pre, a.size() - suf), b.subList(pre, b.size() - suf), no, numbered, out);
        a.subList(a.size() - suf, a.size()).forEach(l -> out.add(context(l, no, numbered)));
        return out;
    }

    private static Line context(String text, int[] no, boolean numbered) {
        Line l = new Line(CONTEXT, numbered ? no[1] : 0, text);
        no[0]++;
        no[1]++;
        return l;
    }

    /** The changed middle: longest common subsequence, or all removed then all added when it is too large. */
    private static void middle(List<String> a, List<String> b, int[] no, boolean numbered, List<Line> out) {
        int n = a.size();
        int m = b.size();
        if ((long)n * m > MAX_CELLS) {
            a.forEach(l -> out.add(new Line(REMOVED, numbered ? no[0]++ : 0, l)));
            b.forEach(l -> out.add(new Line(ADDED, numbered ? no[1]++ : 0, l)));
            return;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int x = n - 1; x >= 0; x--) {
            for (int y = m - 1; y >= 0; y--) {
                lcs[x][y] = a.get(x).equals(b.get(y)) ? lcs[x + 1][y + 1] + 1 : Math.max(lcs[x + 1][y], lcs[x][y + 1]);
            }
        }
        int x = 0;
        int y = 0;
        while (x < n || y < m) {
            if (x < n && y < m && a.get(x).equals(b.get(y))) {
                out.add(context(a.get(x), no, numbered));
                x++;
                y++;
            } else if (x < n && (y == m || lcs[x + 1][y] >= lcs[x][y + 1])) {
                out.add(new Line(REMOVED, numbered ? no[0]++ : 0, a.get(x++)));
            } else {
                out.add(new Line(ADDED, numbered ? no[1]++ : 0, b.get(y++)));
            }
        }
    }

    /**
     * Renders diff lines: {@code  12 + text}, the number column as wide as the largest number.
     *
     * @param lines the lines
     * @param theme the colours
     * @return one styled line each
     */
    static List<AttributedString> render(List<Line> lines, Theme theme) {
        int digits = 0;
        for (Line l : lines) {
            digits = Math.max(digits, l.number() > 0 ? Integer.toString(l.number()).length() : 0);
        }
        List<AttributedString> out = new ArrayList<>();
        for (Line l : lines) {
            String text = Cols.clean(l.text());
            if (l.kind() == HEADER) {
                out.add(Theme.styled(text, theme.faint()));
                continue;
            }
            AttributedStyle style = switch (l.kind()) {
                case ADDED -> theme.ok();
                case REMOVED -> theme.error();
                default -> theme.faint();
            };
            String number = digits == 0 ? "" : pad(l.number() > 0 ? Integer.toString(l.number()) : "", digits) + " ";
            out.add(new AttributedStringBuilder().append(number, theme.faint()).append(l.kind() + " " + text, style)
                .toAttributedString());
        }
        return out;
    }

    /**
     * Styles a question of the approval extension: the tool line, a command preview ({@code $ ...}) in code colour, a
     * diff coloured by {@link #render}, other lines as they are.
     *
     * @param text the question without its answer line, control characters already made visible
     * @param theme the colours
     * @return the lines
     */
    static List<AttributedString> question(String text, Theme theme) {
        List<String> lines = text.lines().toList();
        List<AttributedString> out = new ArrayList<>();
        int diffAt = -1;
        if (containsDiff(text)) {
            for (int i = 0; i < lines.size() && diffAt < 0; i++) {
                diffAt = lines.get(i).startsWith("--- ") ? i : -1;
            }
        }
        int end = diffAt < 0 ? lines.size() : diffAt;
        for (int i = 0; i < end; i++) {
            String l = lines.get(i);
            AttributedStyle style =
                l.startsWith("tool ") ? theme.tool() : l.startsWith("$ ") ? theme.code() : AttributedStyle.DEFAULT;
            out.add(Theme.styled(l.replace("\t", "    "), style));
        }
        if (diffAt >= 0) {
            out.addAll(render(parseUnified(lines.subList(diffAt, lines.size())), theme));
        }
        return out;
    }

    private static String pad(String s, int width) {
        return " ".repeat(Math.max(0, width - s.length())) + s;
    }
}
