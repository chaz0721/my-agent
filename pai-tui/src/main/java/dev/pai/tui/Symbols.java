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
 */
record Symbols(String start, String ok, String fail, String dot) {

    static Symbols forCharset(Charset charset) {
        boolean unicode = charset.newEncoder().canEncode("▸✓✗·");
        return unicode ? new Symbols("▸", "✓", "✗", " · ") : new Symbols(">", "ok", "x", " | ");
    }
}
