/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 9A: theme choice and degradation, column arithmetic. */
class ThemeTest {

    @Test
    void pai_theme_wins_then_no_color_and_dumb_terminals_turn_colour_off() {
        assertThat(Theme.detect(Map.of(), "xterm-256color", 256, false)).isEqualTo(Theme.DARK);
        assertThat(Theme.detect(Map.of("PAI_THEME", "light"), "xterm", 8, false)).isEqualTo(Theme.LIGHT);
        assertThat(Theme.detect(Map.of("PAI_THEME", "none"), "xterm-256color", 256, false).color()).isFalse();
        assertThat(Theme.detect(Map.of("NO_COLOR", "1"), "xterm-256color", 256, false).color()).isFalse();
        assertThat(Theme.detect(Map.of("NO_COLOR", ""), "xterm-256color", 256, false).color()).isTrue();
        assertThat(Theme.detect(Map.of("NO_COLOR", "1", "PAI_THEME", "dark"), "xterm", 8, false)).isEqualTo(Theme.DARK);
        assertThat(Theme.detect(Map.of(), "dumb", null, false).color()).isFalse();
        assertThat(Theme.detect(Map.of(), "vt100", 2, false).color()).isFalse();
        assertThat(Theme.detect(Map.of(), "windows-vtp", null, false).color()).isTrue();
    }

    @Test
    void conhost_gets_the_ascii_spinner_and_windows_terminal_keeps_braille() {
        assertThat(Theme.detect(Map.of(), "windows-vtp", 256, true).spinner()).isEqualTo(Theme.ASCII_SPINNER);
        assertThat(Theme.detect(Map.of("WT_SESSION", "x"), "windows-vtp", 256, true).spinner())
            .isNotEqualTo(Theme.ASCII_SPINNER);
        assertThat(Theme.detect(Map.of("TERM", "xterm"), "xterm", 256, true).spinner())
            .isNotEqualTo(Theme.ASCII_SPINNER);
    }

    @Test
    void colours_are_basic_ansi_only() {
        for (Theme t : new Theme[] {Theme.DARK, Theme.LIGHT}) {
            for (AttributedStyle s : new AttributedStyle[] {t.heading(), t.code(), t.tool(), t.ok(), t.warn(),
                t.error(), t.faint()}) {
                String ansi = new AttributedString("x", s).toAnsi(256, AttributedString.ForceMode.None);
                assertThat(ansi).doesNotContain("38;5;", "38;2;");
            }
        }
        assertThat(new AttributedString("x", Theme.DARK.ok()).toAnsi()).contains("\u001b[32m");
        assertThat(new AttributedString("x", Theme.DARK.error()).toAnsi()).contains("\u001b[91m");
    }

    @Test
    void widths_are_columns_so_cjk_counts_twice() {
        assertThat(Cols.width("abc")).isEqualTo(3);
        assertThat(Cols.width("中文")).isEqualTo(4);
        assertThat(Cols.cut("中文中文中文", 7)).isEqualTo("中文...").satisfies(s -> assertThat(Cols.width(s)).isEqualTo(7));
        assertThat(Cols.cut("short", 10)).isEqualTo("short");
        assertThat(Cols.cut("line one\nline two", 40)).isEqualTo("line one line two");
        assertThat(Cols.head("a中b", 2)).isEqualTo("a");
        assertThat(Cols.pad("中", 4)).isEqualTo("中  ");
    }

    @Test
    void tool_output_is_cleaned_of_escapes_and_carriage_returns() {
        assertThat(Cols.clean("\u001b[1;31mERROR\u001b[0m done")).isEqualTo("ERROR done");
        assertThat(Cols.clean("10%\r50%\r100%")).isEqualTo("100%");
        assertThat(Cols.clean("a\tb")).isEqualTo("a    b");
        assertThat(Cols.clean("bell\u0007")).isEqualTo("bell\\x07");
        assertThat(Cols.clean("\u001b]0;title\u0007x")).isEqualTo("x");
    }

    @Test
    void box_characters_stay_unicode_in_printed_ansi() {
        AttributedString line = new AttributedString("│ a ─┼─", Theme.DARK.faint());
        assertThat(Cols.ansi(line)).contains("│ a ─┼─").contains("\u001b[90m");
        assertThat(Cols.ansi(new AttributedString("plain"))).isEqualTo("plain");
    }

    @Test
    void symbols_fall_back_to_ascii_for_narrow_charsets() {
        Symbols utf = Symbols.forCharset(StandardCharsets.UTF_8);
        assertThat(utf.unicode()).isTrue();
        assertThat(utf.bar()).isEqualTo("│");
        Symbols ascii = Symbols.forCharset(StandardCharsets.US_ASCII);
        assertThat(ascii.unicode()).isFalse();
        assertThat(ascii.start() + ascii.bar() + ascii.bullet() + ascii.corners()[0]).isEqualTo(">|-+");
        Symbols gbk = Symbols.forCharset(Charset.forName("GBK"));
        assertThat(gbk.ok()).isEqualTo("ok");
    }
}
