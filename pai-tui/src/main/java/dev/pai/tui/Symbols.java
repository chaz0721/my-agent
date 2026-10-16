/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.nio.charset.Charset;

/**
 * Glyphs, with ASCII fallbacks for consoles that cannot encode them (for example GBK on Windows).
 *
 * @param start tool started
 * @param ok tool succeeded
 * @param fail tool failed
 * @param dot status separator
 * @param unicode whether the box-drawing and other glyphs below are Unicode
 */
record Symbols(String start, String ok, String fail, String dot, boolean unicode) {

    /** All glyphs Unicode. */
    static final Symbols UNICODE = new Symbols("▸", "✓", "✗", " · ", true);

    static Symbols forCharset(Charset charset) {
        boolean marks = charset.newEncoder().canEncode("▸✓✗·");
        boolean boxes = marks && charset.newEncoder().canEncode("•│─╭╮╰╯❯…┼⠋");
        if (!marks) {
            return new Symbols(">", "ok", "x", " | ", false);
        }
        return boxes ? UNICODE : new Symbols(UNICODE.start(), UNICODE.ok(), UNICODE.fail(), UNICODE.dot(), false);
    }

    /** List bullet. */
    String bullet() {
        return unicode ? "•" : "-";
    }

    /** Vertical rule: code blocks, quotes, table columns, box sides. */
    String bar() {
        return unicode ? "│" : "|";
    }

    /** Horizontal rule. */
    String rule() {
        return unicode ? "─" : "-";
    }

    /** Crossing of rules in a table separator. */
    String cross() {
        return unicode ? "┼" : "+";
    }

    /** Box corners: top left, top right, bottom left, bottom right. */
    String[] corners() {
        return unicode ? new String[] {"╭", "╮", "╰", "╯"} : new String[] {"+", "+", "+", "+"};
    }

    /** Marker of the selected choice. */
    String pointer() {
        return unicode ? "❯" : ">";
    }

    /** Start of a fold hint. */
    String more() {
        return unicode ? "…" : "...";
    }
}
