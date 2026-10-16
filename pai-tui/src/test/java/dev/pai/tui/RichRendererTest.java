/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.StreamEvent;
import dev.pai.model.Usage;

import static org.assertj.core.api.Assertions.assertThat;

/** 9D/9E: the coloured renderer on a virtual terminal: bar, /expand, resize, choice boxes. */
@Tag("slow")
class RichRendererTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final PipedOutputStream keys = new PipedOutputStream();
    private JLineRendererImpl renderer;

    private JLineRendererImpl open(int width, Theme theme, int fold) throws IOException {
        Terminal t = TerminalBuilder.builder().system(false).streams(new PipedInputStream(keys), out)
            .type("xterm-256color").size(new Size(width, 24)).encoding(StandardCharsets.UTF_8)
            .stdinEncoding(StandardCharsets.UTF_8).stdoutEncoding(StandardCharsets.UTF_8).build();
        renderer = new JLineRendererImpl(t, theme, fold);
        return renderer;
    }

    @AfterEach
    void close() throws IOException {
        keys.close();
        if (renderer != null) {
            renderer.close();
        }
    }

    /** What was printed, escape sequences removed. */
    private String output() {
        return raw().replaceAll("\u001b\\[[0-9;?$]*[ -/]*[@-~]|\u001b[78]", "");
    }

    private String raw() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private void type(String s) throws IOException {
        keys.write(s.getBytes(StandardCharsets.UTF_8));
        keys.flush();
    }

    private static List<String> plain(List<AttributedString> lines) {
        return lines.stream().map(AttributedString::toString).toList();
    }

    private static AgentEvent.ToolExecutionEnd bashEnd(ContentBlock.ToolCall call, int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append(i).append('\n');
        }
        return new AgentEvent.ToolExecutionEnd(call,
            new ToolResult(List.of(new ContentBlock.Text(sb + "[exit code: 0]")), false,
                Optional.of(Json.mapper().createObjectNode().put("exitCode", 0))),
            Duration.ofMillis(10));
    }

    @Test
    @Timeout(20)
    void the_bar_shows_mode_model_context_tasks_and_sub_agents() throws IOException {
        JLineRendererImpl r = open(100, Theme.DARK, ToolCard.FOLD_LINES);
        r.status(new StatusLine("openai-compat/deepseek-chat", 23, Usage.ZERO, "abc", StatusLine.IDLE));
        r.sidebar(List.of("tasks 3/5", "  [x] a"));
        List<String> live = plain(r.liveLines());
        assertThat(live).containsExactly("deepseek-chat · ctx 23% · 3/5 tasks");
        r.println("approvals: mode auto-read");
        ContentBlock.ToolCall spawn = new ContentBlock.ToolCall("s1", "spawn_agent",
            Json.mapper().createObjectNode().put("task", "look").put("agentType", "explore"));
        r.accept(new AgentEvent.AgentStart());
        r.accept(new AgentEvent.ToolExecutionStart(spawn));
        r.accept(new AgentEvent.ToolExecutionUpdate("s1", "[sa-9 explore] turn 1: read pom.xml"));
        live = plain(r.liveLines());
        assertThat(live.get(live.size() - 1))
            .startsWith("[AUTO_READ] deepseek-chat · ctx 23% · 3/5 tasks · 1 sub-agent · ").endsWith("s");
        assertThat(live).anyMatch(l -> l.endsWith("spawn_agent explore: look")).contains("  turn 1: read pom.xml");
        r.accept(new AgentEvent.ToolExecutionEnd(spawn, ToolResult.text("ok"), Duration.ofMillis(5)));
        r.accept(new AgentEvent.AgentEnd(List.of()));
        assertThat(plain(r.liveLines())).containsExactly("[AUTO_READ] deepseek-chat · ctx 23% · 3/5 tasks");
        assertThat(output()).contains("spawn_agent explore: look · done (1 turns)", "usage in=0 out=0 (");
        r.println("plan mode on: read-only");
        assertThat(plain(r.liveLines()).get(0)).startsWith("[PLAN]");
    }

    @Test
    @Timeout(20)
    void a_running_card_is_redrawn_whole_after_the_live_area_grows() throws IOException {
        JLineRendererImpl r = open(100, Theme.DARK, ToolCard.FOLD_LINES);
        ContentBlock.ToolCall spawn = new ContentBlock.ToolCall("s1", "spawn_agent",
            Json.mapper().createObjectNode().put("task", "look").put("agentType", "explore"));
        r.accept(new AgentEvent.AgentStart());
        r.accept(new AgentEvent.ToolExecutionStart(spawn));
        r.accept(new AgentEvent.ToolExecutionUpdate("s1", "[sa-9 explore] turn 1: read pom.xml"));
        String s = raw();
        int lastRegion = s.lastIndexOf("r\u001b8");
        assertThat(lastRegion).isPositive();
        // after the scroll region moved for the progress line, the card text itself is written again
        assertThat(s.substring(lastRegion)).contains("spawn_agent", "turn 1: read pom.xml");
    }

    /** Review P1: reasoning reaches the live area of both views with escape and direction controls made harmless. */
    private void reasoningIsClean(Theme theme) throws IOException {
        JLineRendererImpl r = open(100, theme, ToolCard.FOLD_LINES);
        String evil =
            "\u001b]0;pwned\u0007 \u001b[2J \u009b31m \u202eevil \u001b]8;;http://x\u0007l \u001b]52;c;Zm9v\u0007";
        r.accept(new AgentEvent.AgentStart());
        r.accept(new AgentEvent.MessageUpdate(new StreamEvent.ThinkingDelta(0, "done " + evil + "\n" + evil)));
        List<String> live = plain(r.liveLines());
        assertThat(live).anyMatch(l -> l.contains("done")).hasSizeGreaterThan(2);
        assertThat(String.join("", live)).doesNotContain("\u001b", "\u009b", "\u202e", "\u0007");
    }

    @Test
    @Timeout(20)
    void reasoning_cannot_write_escape_sequences_to_the_coloured_live_area() throws IOException {
        reasoningIsClean(Theme.DARK);
    }

    @Test
    @Timeout(20)
    void reasoning_cannot_write_escape_sequences_to_the_uncoloured_live_area() throws IOException {
        reasoningIsClean(Theme.NONE);
    }

    @Test
    @Timeout(20)
    void wide_terminals_keep_the_task_list_beside_the_counter() throws IOException {
        JLineRendererImpl r = open(130, Theme.DARK, ToolCard.FOLD_LINES);
        r.sidebar(List.of("tasks 1/2", "  [x] a", "  [>] b"));
        assertThat(plain(r.liveLines())).hasSize(4).startsWith("tasks 1/2", "  [x] a", "  [>] b");
    }

    @Test
    @Timeout(20)
    void expand_is_answered_by_the_renderer_and_the_next_line_is_returned() throws IOException {
        JLineRendererImpl r = open(80, Theme.DARK, ToolCard.FOLD_LINES);
        ContentBlock.ToolCall bash =
            new ContentBlock.ToolCall("b", "bash", Json.mapper().createObjectNode().put("command", "seq 1 100"));
        r.accept(new AgentEvent.ToolExecutionStart(bash));
        r.accept(bashEnd(bash, 100));
        assertThat(output()).contains("… (+92 lines, /expand 1)").doesNotContain("\n  100");
        type("/expand\r/expand 7\r/expand x\rhello\r");
        assertThat(r.readLine("> ")).isEqualTo("hello");
        String shown = output().replace("\r", "");
        assertThat(shown).contains("$ seq 1 100", "  100", "/expand: no such tool call",
            "usage: /expand [card number]");
    }

    @Test
    @Timeout(20)
    void fold_zero_shows_everything() throws IOException {
        JLineRendererImpl r = open(80, Theme.DARK, 0);
        ContentBlock.ToolCall bash =
            new ContentBlock.ToolCall("b", "bash", Json.mapper().createObjectNode().put("command", "seq 1 30"));
        r.accept(new AgentEvent.ToolExecutionStart(bash));
        r.accept(bashEnd(bash, 30));
        assertThat(output()).contains("  30").doesNotContain("/expand");
        assertThat(RendererFactory.fold(Map.of("PAI_TUI_FOLD", "0"))).isZero();
        assertThat(RendererFactory.fold(Map.of("PAI_TUI_FOLD", "3"))).isEqualTo(3);
        assertThat(RendererFactory.fold(Map.of("PAI_TUI_FOLD", "x"))).isEqualTo(ToolCard.FOLD_LINES);
        assertThat(RendererFactory.fold(Map.of())).isEqualTo(ToolCard.FOLD_LINES);
    }

    @Test
    @Timeout(20)
    void after_a_resize_every_live_line_fits() throws IOException {
        JLineRendererImpl r = open(100, Theme.DARK, ToolCard.FOLD_LINES);
        r.status(new StatusLine("p/" + "m".repeat(80), 5, Usage.ZERO, "abc", StatusLine.IDLE));
        r.accept(new AgentEvent.AgentStart());
        r.accept(new AgentEvent.ToolExecutionStart(new ContentBlock.ToolCall("b", "bash",
            Json.mapper().createObjectNode().put("command", "echo " + "中".repeat(50)))));
        Terminal t = terminalOf(r);
        t.setSize(new Size(30, 10));
        t.raise(Terminal.Signal.WINCH);
        assertThat(r.liveLines()).allSatisfy(l -> assertThat(l.columnLength()).isLessThanOrEqualTo(30));
    }

    private static Terminal terminalOf(JLineRendererImpl r) {
        try {
            java.lang.reflect.Field f = JLineRendererImpl.class.getDeclaredField("terminal");
            f.setAccessible(true);
            return (Terminal)f.get(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @Timeout(20)
    void approvals_need_a_highlighted_choice_and_enter_and_type_ahead_never_answers() throws Exception {
        JLineRendererImpl r = open(80, Theme.DARK, ToolCard.FOLD_LINES);
        TerminalInteractionImpl ui = new TerminalInteractionImpl(r);
        String question = "tool edit (write)\n--- /p/a.txt\n+++ /p/a.txt\n@@ -1,1 +1,1 @@\n-old\n+new\n"
            + "allow? y = yes, n = no, a = always for edit in this session, A = always, saved as a rule (shown before "
            + "saving)";
        List<String> choices = List.of("y", "n", "a", "A");
        // typed while the turn was running, before the box appeared: never an answer
        type("Add tests\r");
        assertThat(askThenType(ui, question, choices, "\u001b[B\u001b[B\r")).isEqualTo("n");
        assertThat(askThenType(ui, question, choices, "A\r")).isEqualTo("A");
        assertThat(askThenType(ui, question, choices, "\u001b[1;5A\u001b[24~\r3\r")).isEqualTo("a");
        assertThat(askThenType(ui, question, choices, "\u001b")).isEqualTo("n");
        // Ctrl+D: a Ctrl+C typed before raw mode starts would be a signal in this virtual terminal
        assertThat(askThenType(ui, "delete?\n(y = yes, n = no)", List.of("y", "n"), "q\u0004")).isEqualTo("n");
        assertThat(askThenType(ui, "pick one\n(x = this, z = that)", List.of("x", "z"), "\u001b")).isEmpty();
        // nothing of what was typed for the boxes reaches the input line
        type("hello\r");
        assertThat(r.readLine("> ")).isEqualTo("hello");
        assertThat(output()).contains("allow? ❯ n (no)", "allow? ❯ A (always, saved as a rule (shown before saving))",
            "allow? ❯ a (always for edit in this session)", "allow? ❯ cancelled n (no)", "choose ❯ cancelled n (no)");
        assertThat(raw()).contains("\u001b[32m+ new").contains("\u001b[91m- old");
    }

    /** Asks on another thread and types the keys once the box is up (after its guard time). */
    private String askThenType(TerminalInteractionImpl ui, String q, List<String> choices, String keys)
        throws Exception {
        CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> ui.ask(q, choices));
        Thread.sleep(Picker.GUARD_MILLIS + 250);
        type(keys);
        return answer.get(5, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(20)
    void windows_terminal_gets_unicode_glyphs_even_with_a_gbk_code_page() throws IOException {
        Charset gbk = Charset.forName("GBK");
        Terminal t = TerminalBuilder.builder().system(false).streams(new PipedInputStream(keys), out)
            .type("xterm-256color").size(new Size(80, 24)).encoding(gbk).build();
        renderer = new JLineRendererImpl(t, Theme.DARK, ToolCard.FOLD_LINES, true);
        renderer.accept(new AgentEvent.ToolExecutionStart(Events.READ));
        renderer.accept(new AgentEvent.ToolExecutionEnd(Events.READ, ToolResult.text("1\ta\n"), Duration.ZERO));
        // the glyph choice, before encoding: on Windows JLine writes UTF-16 to the console, so no byte check here
        assertThat(plain(renderer.liveLines()).get(0)).contains(" · ctx 0%");
        JLineRendererImpl mono = new JLineRendererImpl(TerminalBuilder.builder().system(false)
            .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).type("xterm-256color")
            .encoding(gbk).build(), Theme.NONE, ToolCard.FOLD_LINES, true);
        mono.accept(new AgentEvent.ToolExecutionStart(Events.READ));
        assertThat(plain(mono.liveLines())).anyMatch(l -> l.startsWith("> read"));
        mono.close();
    }

    @Test
    void the_box_lists_the_meanings_parsed_from_the_question() {
        Picker p = Picker.of("Remember: use mvn -q\n(y = save, n = skip, e = save and edit in the file)",
            List.of("y", "n", "e"));
        assertThat(p.title()).isEqualTo("choose");
        assertThat(p.body()).isEqualTo("Remember: use mvn -q");
        assertThat(p.options()).extracting(Picker.Option::label).containsExactly("save", "skip",
            "save and edit in the file");
        List<String> box = plain(p.lines(Theme.DARK, Symbols.forCharset(StandardCharsets.UTF_8), 60));
        assertThat(box.get(0)).startsWith("╭─ choose ").endsWith("╮");
        assertThat(box.get(1)).startsWith("│   y  save").endsWith("│");
        assertThat(box.get(4)).startsWith("╰").endsWith("╯");
        assertThat(box).allSatisfy(l -> assertThat(Cols.width(l)).isEqualTo(Cols.width(box.get(0))));
        Picker plainQuestion = Picker.of("This directory has project configuration. Trust it?", List.of("y", "n"));
        assertThat(plainQuestion.body()).contains("Trust it?");
        assertThat(plainQuestion.options()).extracting(Picker.Option::label).containsExactly("yes", "no");
    }

    @Test
    @Timeout(20)
    void without_colour_questions_stay_on_the_input_line() throws IOException {
        JLineRendererImpl r = open(80, Theme.NONE, ToolCard.FOLD_LINES);
        assertThat(r.choose("ok?", List.of("y", "n"))).isEmpty();
        type("y\r");
        assertThat(new TerminalInteractionImpl(r).confirm("ok?")).isTrue();
        assertThat(output()).contains("ok?", "[y/n]");
    }
}
