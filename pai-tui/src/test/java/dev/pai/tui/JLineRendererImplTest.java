/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jline.reader.EndOfFileException;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.pai.model.Usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("slow")
class JLineRendererImplTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private Terminal terminal(InputStream in, String type) throws IOException {
        return TerminalBuilder.builder().system(false).streams(in, out).type(type).size(new Size(80, 24))
            .encoding(StandardCharsets.UTF_8).stdinEncoding(StandardCharsets.UTF_8)
            .stdoutEncoding(StandardCharsets.UTF_8).build();
    }

    @Test
    @Timeout(20)
    void streams_events_keeps_live_area_bounded_and_reads_lines() throws IOException {
        PipedOutputStream keys = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(keys);
        Terminal t = terminal(in, "xterm-256color");
        JLineRendererImpl r = new JLineRendererImpl(t);
        r.status(new StatusLine("openai-compat/deepseek-chat", 12, new Usage(1500L, 20L, 0L, 0L), "abc12345",
            StatusLine.IDLE));
        r.accept(Events.run().get(2));
        r.accept(Events.run().get(3));
        List<AttributedString> live = r.liveLines();
        assertThat(live).hasSizeLessThanOrEqualTo(JLineRendererImpl.THINKING_LINES + 1);
        assertThat(live.get(live.size() - 1).toString()).contains("openai-compat/deepseek-chat", "ctx 12%",
            "in 1.5k out 20", "thinking");
        assertThat(live.get(0).toString()).isEqualTo("second");
        Events.run().subList(4, Events.run().size()).forEach(r);
        assertThat(r.liveLines()).hasSize(1);
        String text = out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(text).contains("Reading the file", "✓ read src/Main.java (3 lines, 0.03s)", "error: boom");

        t.setSize(new Size(30, 10));
        t.raise(Terminal.Signal.WINCH);
        assertThat(r.liveLines()).allSatisfy(l -> assertThat(l.columnLength()).isLessThanOrEqualTo(30));

        keys.write("hello\r".getBytes(StandardCharsets.UTF_8));
        keys.flush();
        assertThat(r.readLine("> ")).isEqualTo("hello");
        keys.close();
        assertThatThrownBy(() -> r.readLine("> ")).isInstanceOf(EndOfFileException.class);
        r.close();
    }

    @Test
    @Timeout(20)
    void dumb_terminal_prints_tool_starts_as_lines() throws IOException {
        JLineRendererImpl r = new JLineRendererImpl(terminal(new ByteArrayInputStream(new byte[0]), "dumb"));
        Events.run().forEach(r);
        r.println("after");
        r.close();
        assertThat(out.toString(StandardCharsets.UTF_8))
            .contains("▸ read src/Main.java", "✓ read src/Main.java (3 lines, 0.03s)", "after")
            .doesNotContain("first thought", "fourth");
    }

    @Test
    @Timeout(20)
    void the_sidebar_sits_above_the_status_line_and_shift_tab_runs_the_mode_action() throws IOException {
        PipedOutputStream keys = new PipedOutputStream();
        Terminal t = terminal(new PipedInputStream(keys), "xterm-256color");
        JLineRendererImpl r = new JLineRendererImpl(t);
        r.sidebar(List.of("tasks 1/2", "  [x] a", "  [>] doing b"));
        List<AttributedString> live = r.liveLines();
        assertThat(live).extracting(AttributedString::toString).startsWith("tasks 1/2", "  [x] a", "  [>] doing b");
        assertThat(live).hasSize(4);
        r.sidebar(List.of());
        assertThat(r.liveLines()).hasSize(1);
        List<String> cycled = new ArrayList<>();
        r.onModeCycle(() -> {
            cycled.add("cycle");
            r.println("approvals: mode plan");
        });
        keys.write((JLineRendererImpl.SHIFT_TAB + "ok\r").getBytes(StandardCharsets.UTF_8));
        keys.flush();
        assertThat(r.readLine("> ")).isEqualTo("ok");
        assertThat(cycled).containsExactly("cycle");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("approvals: mode plan");
        keys.close();
        r.close();
    }
}
