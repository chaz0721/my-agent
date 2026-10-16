/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * Renders streamed assistant Markdown line by line: headings, bullet and numbered lists, quotes, rules, inline code,
 * bold and italic as each line completes; fenced code blocks (a bar on the left, code colour, no syntax highlighting)
 * and pipe tables (columns aligned, or left as they are when wider than the terminal) once the block ends. The
 * unfinished last line is not printed but offered as {@link #pending()} for the live area. Control characters from the
 * model are made visible.
 */
final class Markdown {

    private static final Pattern HEADING = Pattern.compile("(#{1,6})\\s+(.*)");
    private static final Pattern BULLET = Pattern.compile("(\\s*)[-*+]\\s+(.*)");
    private static final Pattern NUMBERED = Pattern.compile("(\\s*)(\\d{1,3}[.)])\\s+(.*)");
    private static final Pattern QUOTE = Pattern.compile("\\s*>\\s?(.*)");
    private static final Pattern RULE = Pattern.compile("\\s*([-*_])(\\s*\\1){2,}\\s*");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("\\s*:?-+:?\\s*");
    private static final String FENCE = "```";
    private static final String TABLE_GAP = " ";
    private static final int MAX_RULE = 80;

    private final Theme theme;
    private final Symbols symbols;
    private final IntSupplier width;
    private final StringBuilder partial = new StringBuilder();
    private final List<String> block = new ArrayList<>();
    /** Language of the open code block; null outside one. */
    private String fence;
    private boolean table;

    Markdown(Theme theme, Symbols symbols, IntSupplier width) {
        this.theme = theme;
        this.symbols = symbols;
        this.width = width;
    }

    /**
     * Takes streamed text.
     *
     * @param delta the text
     * @return the lines it completes, rendered
     */
    List<AttributedString> feed(String delta) {
        List<AttributedString> out = new ArrayList<>();
        for (int i = 0; i < delta.length(); i++) {
            char c = delta.charAt(i);
            if (c == '\n') {
                line(partial.toString(), out);
                partial.setLength(0);
            } else {
                partial.append(c);
            }
        }
        return out;
    }

    /**
     * Ends the text: renders the unfinished line and any open block.
     *
     * @return the remaining lines
     */
    List<AttributedString> finish() {
        List<AttributedString> out = new ArrayList<>();
        if (!partial.isEmpty()) {
            line(partial.toString(), out);
            partial.setLength(0);
        }
        closeBlock(out);
        return out;
    }

    /**
     * Describes what is not printed yet, for the live area.
     *
     * @return the unfinished line or a note on the open block; empty when nothing is held back
     */
    String pending() {
        if (fence != null) {
            return symbols.more() + " code block, " + block.size() + " lines";
        }
        if (table) {
            return symbols.more() + " table, " + block.size() + " rows";
        }
        return Cols.clean(partial.toString());
    }

    private void line(String raw, List<AttributedString> out) {
        String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
        if (fence != null) {
            if (line.strip().startsWith(FENCE)) {
                closeBlock(out);
            } else {
                block.add(line);
            }
            return;
        }
        if (line.strip().startsWith(FENCE)) {
            closeBlock(out);
            fence = line.strip().substring(FENCE.length()).strip();
            return;
        }
        if (line.strip().startsWith("|")) {
            table = true;
            block.add(line);
            return;
        }
        closeBlock(out);
        out.add(render(line));
    }

    private void closeBlock(List<AttributedString> out) {
        if (fence != null) {
            if (!fence.isEmpty()) {
                out.add(Theme.styled(symbols.bar() + " " + Cols.clean(fence), theme.faint()));
            }
            for (String l : block) {
                out.add(new AttributedStringBuilder().append(symbols.bar() + " ", theme.faint())
                    .append(Cols.clean(l), theme.code()).toAttributedString());
            }
        } else if (table) {
            out.addAll(table(block));
        }
        fence = null;
        table = false;
        block.clear();
    }

    /** Renders one line outside blocks. */
    AttributedString render(String line) {
        Matcher m = HEADING.matcher(line);
        if (m.matches()) {
            return inline(m.group(2), theme.heading());
        }
        if (RULE.matcher(line).matches()) {
            return Theme.styled(symbols.rule().repeat(Math.min(MAX_RULE, Math.max(1, width.getAsInt()))),
                theme.faint());
        }
        m = BULLET.matcher(line);
        if (m.matches()) {
            return prefixed(m.group(1) + symbols.bullet() + " ", AttributedStyle.DEFAULT, m.group(2));
        }
        m = NUMBERED.matcher(line);
        if (m.matches()) {
            return prefixed(m.group(1) + m.group(2) + " ", AttributedStyle.DEFAULT, m.group(3));
        }
        m = QUOTE.matcher(line);
        if (m.matches()) {
            return prefixed(symbols.bar() + " ", theme.faint(), m.group(1));
        }
        return inline(line, AttributedStyle.DEFAULT);
    }

    private AttributedString prefixed(String prefix, AttributedStyle style, String rest) {
        return new AttributedStringBuilder().append(prefix, style).append(inline(rest, AttributedStyle.DEFAULT))
            .toAttributedString();
    }

    /** Inline code ({@code `x`}), bold ({@code **x**}) and italic ({@code *x*}); unmatched markers stay literal. */
    AttributedString inline(String raw, AttributedStyle base) {
        String s = Cols.clean(raw);
        AttributedStringBuilder sb = new AttributedStringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int close;
            if (c == '`' && (close = s.indexOf('`', i + 1)) > i) {
                sb.append(s.substring(i + 1, close), theme.code());
                i = close + 1;
            } else if (s.startsWith("**", i) && (close = s.indexOf("**", i + 2)) > i + 2) {
                sb.append(inline(s.substring(i + 2, close), base.bold()));
                i = close + 2;
            } else if (c == '*' && i + 1 < s.length() && s.charAt(i + 1) != ' '
                && (close = s.indexOf('*', i + 1)) > i + 1) {
                sb.append(s.substring(i + 1, close), base.italic());
                i = close + 1;
            } else {
                sb.append(String.valueOf(c), base);
                i++;
            }
        }
        return sb.toAttributedString();
    }

    /** Aligns a pipe table; rows wider than the terminal leave the table as written. */
    private List<AttributedString> table(List<String> rows) {
        List<List<AttributedString>> cells = new ArrayList<>();
        List<Boolean> separators = new ArrayList<>();
        int columns = 0;
        for (String row : rows) {
            List<String> parts = split(row);
            separators.add(parts.stream().allMatch(p -> TABLE_SEPARATOR.matcher(p).matches()));
            List<AttributedString> rendered = new ArrayList<>();
            parts.forEach(p -> rendered.add(inline(p.strip(), AttributedStyle.DEFAULT)));
            cells.add(rendered);
            columns = Math.max(columns, parts.size());
        }
        int[] widths = new int[columns];
        for (int r = 0; r < cells.size(); r++) {
            for (int c = 0; !separators.get(r) && c < cells.get(r).size(); c++) {
                widths[c] = Math.max(widths[c], cells.get(r).get(c).columnLength());
            }
        }
        int total = (columns - 1) * (TABLE_GAP.length() * 2 + 1);
        for (int w : widths) {
            total += w;
        }
        List<AttributedString> out = new ArrayList<>();
        if (total > width.getAsInt()) {
            rows.forEach(r -> out.add(inline(r, AttributedStyle.DEFAULT)));
            return out;
        }
        boolean header = separators.size() > 1 && separators.get(1);
        for (int r = 0; r < cells.size(); r++) {
            out.add(separators.get(r) ? separatorRow(widths) : row(cells.get(r), widths, header && r == 0));
        }
        return out;
    }

    private AttributedString row(List<AttributedString> cells, int[] widths, boolean header) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        for (int c = 0; c < widths.length; c++) {
            if (c > 0) {
                sb.append(TABLE_GAP + symbols.bar() + TABLE_GAP, theme.faint());
            }
            AttributedString cell = c < cells.size() ? cells.get(c) : AttributedString.EMPTY;
            sb.append(header ? new AttributedString(cell, AttributedStyle.BOLD) : cell);
            if (c < widths.length - 1) {
                sb.append(" ".repeat(widths[c] - cell.columnLength()));
            }
        }
        return sb.toAttributedString();
    }

    private AttributedString separatorRow(int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < widths.length; c++) {
            if (c > 0) {
                sb.append(symbols.rule()).append(symbols.cross()).append(symbols.rule());
            }
            sb.append(symbols.rule().repeat(widths[c]));
        }
        return Theme.styled(sb.toString(), theme.faint());
    }

    /** Cells of a pipe row: the text between unescaped pipes, without the outer ones. */
    private static List<String> split(String row) {
        String s = row.strip();
        s = s.startsWith("|") ? s.substring(1) : s;
        s = s.endsWith("|") && !s.endsWith("\\|") ? s.substring(0, s.length() - 1) : s;
        List<String> parts = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '|') {
                parts.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        parts.add(cell.toString());
        return parts;
    }
}
