/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.regex.Pattern;

import org.jline.utils.AttributedCharSequence;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.WCWidth;

/**
 * Terminal column arithmetic: every width in the UI is counted in columns ({@link WCWidth}), so that CJK characters and
 * emoji (two columns) line up; and cleaning of tool output for display.
 */
final class Cols {

    /** What a cut line ends with. */
    static final String ELLIPSIS = "...";
    private static final int TAB_COLUMNS = 4;
    /** CSI and OSC escape sequences, as coloured tool output (compilers, git) contains them. */
    private static final Pattern ANSI =
        Pattern.compile("\u001b\\[[0-9;?]*[ -/]*[@-~]" + "|\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)?");

    /** Box-drawing characters JLine would replace by ASCII when printing without a terminal. */
    private static final String BOX = "│─┼┌┐└┘├┤┬┴";
    /** First private-use code point standing in for {@link #BOX} while JLine encodes styles. */
    private static final char PRIVATE_USE = '\ue000';
    private static final int BASIC_COLORS = 16;

    private Cols() {}

    /**
     * Encodes a styled line as ANSI in the 16 basic colours, keeping box-drawing characters as Unicode (JLine replaces
     * them by ASCII unless it may switch the terminal to the DEC line-drawing set, which some consoles render as
     * letters).
     *
     * @param line the line
     * @return the escape sequences and text
     */
    static String ansi(AttributedString line) {
        StringBuilder shielded = new StringBuilder(line.toString());
        boolean any = false;
        for (int i = 0; i < shielded.length(); i++) {
            int k = BOX.indexOf(shielded.charAt(i));
            if (k >= 0) {
                shielded.setCharAt(i, (char)(PRIVATE_USE + k));
                any = true;
            }
        }
        if (!any) {
            return line.toAnsi(BASIC_COLORS, AttributedCharSequence.ForceMode.None);
        }
        AttributedStringBuilder sb = new AttributedStringBuilder();
        for (int i = 0; i < line.length(); i++) {
            sb.append(String.valueOf(shielded.charAt(i)), line.styleAt(i));
        }
        StringBuilder out =
            new StringBuilder(sb.toAttributedString().toAnsi(BASIC_COLORS, AttributedCharSequence.ForceMode.None));
        for (int i = 0; i < out.length(); i++) {
            int k = out.charAt(i) - PRIVATE_USE;
            if (k >= 0 && k < BOX.length()) {
                out.setCharAt(i, BOX.charAt(k));
            }
        }
        return out.toString();
    }

    /**
     * Returns the columns a text takes.
     *
     * @param s text without control characters
     * @return the width
     */
    static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i = s.offsetByCodePoints(i, 1)) {
            w += Math.max(0, WCWidth.wcwidth(s.codePointAt(i)));
        }
        return w;
    }

    /**
     * Flattens a text to one line and cuts it to a number of columns, ending a cut text with {@value #ELLIPSIS}.
     *
     * @param s the text
     * @param max the columns available
     * @return the line
     */
    static String cut(String s, int max) {
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        if (width(flat) <= max) {
            return flat;
        }
        return head(flat, Math.max(0, max - ELLIPSIS.length())) + ELLIPSIS;
    }

    /**
     * Returns the longest prefix of a text that fits in a number of columns.
     *
     * @param s the text
     * @param max the columns
     * @return the prefix
     */
    static String head(String s, int max) {
        int w = 0;
        int i = 0;
        while (i < s.length()) {
            int cw = Math.max(0, WCWidth.wcwidth(s.codePointAt(i)));
            if (w + cw > max) {
                break;
            }
            w += cw;
            i = s.offsetByCodePoints(i, 1);
        }
        return s.substring(0, i);
    }

    /**
     * Cuts a styled line to a number of columns.
     *
     * @param s the line
     * @param max the columns
     * @return the line, unchanged when it fits
     */
    static AttributedString fit(AttributedString s, int max) {
        return s.columnLength() <= max ? s : s.columnSubSequence(0, Math.max(0, max));
    }

    /**
     * Pads a text with spaces to a number of columns.
     *
     * @param s the text
     * @param cols the columns
     * @return the padded text
     */
    static String pad(String s, int cols) {
        return s + " ".repeat(Math.max(0, cols - width(s)));
    }

    /**
     * Makes one line of model or tool output safe and tidy to show: escape sequences removed, a carriage return keeps
     * only what follows it (progress bars), tabs become spaces and other control characters are made visible.
     *
     * @param line one line, without its newline
     * @return the line to show
     */
    static String clean(String line) {
        String s = ANSI.matcher(line).replaceAll("");
        int cr = s.stripTrailing().lastIndexOf('\r');
        if (cr >= 0) {
            s = s.substring(cr + 1);
        }
        return TerminalInteractionImpl.visible(s.replace("\r", "")).replace("\t", " ".repeat(TAB_COLUMNS));
    }
}
