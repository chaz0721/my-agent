/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.jline.reader.EndOfFileException;
import org.junit.jupiter.api.Test;

import dev.pai.model.Usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlainRendererImplTest {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private PlainRendererImpl renderer(String input, Charset outCharset) {
        return new PlainRendererImpl(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8, new PrintStream(bytes, true, outCharset));
    }

    private String out(Charset c) {
        return bytes.toString(c).replace("\r\n", "\n");
    }

    @Test
    void renders_text_tools_notices_and_usage_without_reasoning() {
        PlainRendererImpl r = renderer("", StandardCharsets.UTF_8);
        Events.run().forEach(r);
        String out = out(StandardCharsets.UTF_8);
        assertThat(out).contains("Reading the file\n▸ read src/Main.java\n", "✓ read src/Main.java (3 lines, 0.03s)\n",
            "[context compacted: 12 -> 5 messages]\n", "error: boom\n", "[usage in=0 out=0 cacheRead=0 cacheWrite=0]");
        assertThat(out).doesNotContain("first thought");
    }

    @Test
    void falls_back_to_ascii_when_the_charset_cannot_encode_symbols() {
        Charset gbk = Charset.forName("GBK");
        PlainRendererImpl r = renderer("", gbk);
        Events.run().forEach(r);
        assertThat(out(gbk)).contains("> read src/Main.java", "ok read src/Main.java (3 lines, 0.03s)");
    }

    @Test
    void reads_lines_until_end_of_input_and_prints_safely() throws IOException {
        PlainRendererImpl r = renderer("hello\nsecond\n", StandardCharsets.UTF_8);
        assertThat(r.readLine("> ")).isEqualTo("hello");
        r.accept(new dev.pai.core.loop.AgentEvent.MessageUpdate(new dev.pai.model.StreamEvent.TextDelta(0, "partial")));
        r.println("notice");
        assertThat(r.readLine("> ")).isEqualTo("second");
        assertThatThrownBy(() -> r.readLine("> ")).isInstanceOf(EndOfFileException.class);
        r.status(new StatusLine("m", 1, Usage.ZERO, "s", StatusLine.IDLE));
        r.close();
        assertThat(out(StandardCharsets.UTF_8)).isEqualTo("> \npartial\nnotice\n> \n> \n");
    }

    @Test
    void factory_uses_jline_only_on_interactive_non_dumb_terminals() {
        assertThat(RendererFactory.useTerminalUi(true, Map.of())).isTrue();
        assertThat(RendererFactory.useTerminalUi(true, Map.of("TERM", "xterm-256color"))).isTrue();
        assertThat(RendererFactory.useTerminalUi(true, Map.of("TERM", "dumb"))).isFalse();
        assertThat(RendererFactory.useTerminalUi(false, Map.of())).isFalse();
        assertThat(RendererFactory.isTerminal(null)).isFalse();
    }
}
