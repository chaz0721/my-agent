/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 9B: streamed Markdown, asserted on the plain projection, colours sampled on the ANSI form. */
class MarkdownTest {

    private static final Symbols UTF = Symbols.forCharset(StandardCharsets.UTF_8);

    private static Markdown md(int width) {
        return new Markdown(Theme.DARK, UTF, () -> width);
    }

    /** Streams the text in small pieces, as a provider does, and returns every printed line. */
    private static List<AttributedString> stream(Markdown m, String text, int chunk) {
        List<AttributedString> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunk) {
            out.addAll(m.feed(text.substring(i, Math.min(text.length(), i + chunk))));
        }
        out.addAll(m.finish());
        return out;
    }

    private static List<String> plain(List<AttributedString> lines) {
        return lines.stream().map(AttributedString::toString).toList();
    }

    @Test
    void headings_lists_quotes_rules_and_inline_markup() {
        List<AttributedString> out = stream(md(40),
            "# Title\n- one `x`\n  * two\n3. three **b**\n> said\n---\nplain *it* and **bold** text\n", 3);
        assertThat(plain(out)).containsExactly("Title", "• one x", "  • two", "3. three b", "│ said", "─".repeat(40),
            "plain it and bold text");
        assertThat(out.get(0).toAnsi()).contains("\u001b[96;1m");
        assertThat(out.get(1).toAnsi()).contains("\u001b[33mx");
        assertThat(out.get(6).toAnsi()).contains("\u001b[1mbold");
        assertThat(out.get(6).toAnsi()).contains("\u001b[3mit");
    }

    @Test
    void unmatched_markers_stay_literal_and_control_characters_are_visible() {
        List<String> out = plain(stream(md(80), "a * b ** c ` d\nesc \u001b[2J here\n", 5));
        assertThat(out).containsExactly("a * b ** c ` d", "esc  here");
        assertThat(plain(stream(md(80), "x\u0007y\n", 1))).containsExactly("x\\x07y");
    }

    @Test
    void lines_are_printed_when_they_end_and_the_rest_waits_in_pending() {
        Markdown m = md(80);
        assertThat(m.feed("Hello wor")).isEmpty();
        assertThat(m.pending()).isEqualTo("Hello wor");
        assertThat(plain(m.feed("ld\nnext"))).containsExactly("Hello world");
        assertThat(m.pending()).isEqualTo("next");
        assertThat(plain(m.finish())).containsExactly("next");
        assertThat(m.pending()).isEmpty();
    }

    @Test
    void code_blocks_are_held_until_the_fence_closes() {
        Markdown m = md(80);
        assertThat(m.feed("```java\nclass A {\n")).isEmpty();
        assertThat(m.pending()).contains("code block, 1 lines");
        List<AttributedString> out = m.feed("\tint x; // 注释\n}\n```\nafter\n");
        assertThat(plain(out)).containsExactly("│ java", "│ class A {", "│     int x; // 注释", "│ }", "after");
        assertThat(out.get(1).toAnsi()).contains("\u001b[33mclass A {");
        assertThat(plain(stream(md(80), "```\nunclosed", 4))).containsExactly("│ unclosed");
    }

    @Test
    void tables_align_by_columns_with_cjk_counted_twice() {
        List<String> out =
            plain(stream(md(80), "| 名称 | value |\n|------|------:|\n| 中文字 | 12 |\n| ascii | 3456 |\nend\n", 7));
        assertThat(out).containsExactly("名称   │ value", "───────┼──────", "中文字 │ 12", "ascii  │ 3456", "end");
        int bar = Cols.width(out.get(0).substring(0, out.get(0).indexOf('│')));
        assertThat(out.subList(2, 4))
            .allSatisfy(l -> assertThat(Cols.width(l.substring(0, l.indexOf('│')))).isEqualTo(bar));
    }

    @Test
    void a_table_wider_than_the_terminal_is_left_as_written() {
        String row = "| " + "x".repeat(30) + " | " + "y".repeat(30) + " |";
        assertThat(plain(stream(md(40), row + "\n|---|---|\n", 9))).containsExactly(row, "|---|---|");
    }

    @Test
    void escaped_pipes_stay_in_their_cell() {
        assertThat(plain(stream(md(80), "| a \\| b | c |\n", 80))).containsExactly("a | b │ c");
    }
}
