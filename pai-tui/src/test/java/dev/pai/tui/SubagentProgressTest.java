/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;

/** M8.5C: the per-turn lines of a {@code spawn_agent} call fold into one progress line and close with a done line. */
class SubagentProgressTest {

    private static final ContentBlock.ToolCall SPAWN = new ContentBlock.ToolCall("c9", "spawn_agent",
        Json.mapper().createObjectNode().put("task", "find callers").put("agentType", "explore"));
    private static final ContentBlock.ToolCall BASH =
        new ContentBlock.ToolCall("c2", "bash", Json.mapper().createObjectNode().put("command", "mvn verify"));

    /** Records what the view draws; progress lines are kept per call, so a later one replaces the earlier. */
    private static final class FakeSurface implements EventView.Surface {
        final Map<String, String> progress = new LinkedHashMap<>();
        final List<String> progressCalls = new ArrayList<>();
        final List<String> lines = new ArrayList<>();

        @Override
        public void text(String delta) {
            // not used here
        }

        @Override
        public void line(String line) {
            lines.add(line);
        }

        @Override
        public void thinking(String delta) {
            // not used here
        }

        @Override
        public void toolStarted(String callId, String line) {
            // not used here
        }

        @Override
        public void toolFinished(String callId, String line) {
            progress.remove(callId);
            lines.add(line);
        }

        @Override
        public void progress(String callId, String line) {
            progress.put(callId, line);
            progressCalls.add(line);
        }

        @Override
        public void phase(String phase) {
            // not used here
        }
    }

    private static List<AgentEvent> run() {
        return List.of(new AgentEvent.ToolExecutionStart(SPAWN), new AgentEvent.ToolExecutionStart(BASH),
            new AgentEvent.ToolExecutionUpdate("c9", "[sa-1a2b3c4d explore] turn 1: read pom.xml; grep effectOf"),
            new AgentEvent.ToolExecutionUpdate("c2", "BUILD SUCCESS"),
            new AgentEvent.ToolExecutionUpdate("c9", "[sa-1a2b3c4d explore] turn 2: " + "x".repeat(200)),
            new AgentEvent.ToolExecutionUpdate("c9", "[sa-1a2b3c4d explore] turn 3: replied"),
            new AgentEvent.ToolExecutionEnd(BASH, ToolResult.text("ok"), Duration.ofMillis(10)),
            new AgentEvent.ToolExecutionEnd(SPAWN, ToolResult.text("[sub-agent sa-1a2b3c4d (explore): done]"),
                Duration.ofMillis(1200)));
    }

    @Test
    void updates_replace_one_line_per_run_and_the_end_closes_it() {
        FakeSurface s = new FakeSurface();
        EventView view = new EventView(s, Symbols.forCharset(StandardCharsets.UTF_8));
        List<AgentEvent> events = run();
        events.subList(0, 5).forEach(view);
        assertThat(s.progress).containsOnlyKeys("c9");
        assertThat(s.progress.get("c9")).startsWith("[sub-agent sa-1a2b3c4d] turn 2: xxx").endsWith("...")
            .hasSize("[sub-agent sa-1a2b3c4d] turn 2: ".length() + EventView.MAX_SUMMARY_CHARS);
        events.subList(5, events.size()).forEach(view);
        assertThat(s.progressCalls).hasSize(3).noneMatch(l -> l.contains("BUILD SUCCESS"));
        assertThat(s.progress).isEmpty();
        assertThat(s.lines).hasSize(3);
        assertThat(s.lines.get(1)).isEqualTo("[sub-agent sa-1a2b3c4d] done (3 turns)");
        assertThat(s.lines.get(2)).startsWith("✓ spawn_agent");
    }

    @Test
    void a_background_spawn_without_updates_prints_no_done_line() {
        FakeSurface s = new FakeSurface();
        EventView view = new EventView(s, Symbols.forCharset(StandardCharsets.UTF_8));
        view.accept(new AgentEvent.ToolExecutionStart(SPAWN));
        view.accept(new AgentEvent.ToolExecutionUpdate("c9", "not a turn line"));
        view.accept(new AgentEvent.ToolExecutionEnd(SPAWN, ToolResult.text("started sa-1"), Duration.ZERO));
        assertThat(s.progressCalls).isEmpty();
        assertThat(s.lines).singleElement().asString().startsWith("✓ spawn_agent");
    }

    @Test
    void the_plain_renderer_prints_every_update_on_its_own_line() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PlainRendererImpl r = new PlainRendererImpl(new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8,
            new PrintStream(bytes, true, StandardCharsets.UTF_8));
        run().forEach(r);
        String out = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(out).contains("[sub-agent sa-1a2b3c4d] turn 1: read pom.xml; grep effectOf\n",
            "[sub-agent sa-1a2b3c4d] turn 3: replied\n✓ $ mvn verify",
            "[sub-agent sa-1a2b3c4d] done (3 turns)\n✓ spawn_agent").doesNotContain("BUILD SUCCESS");
    }

    @Test
    @Tag("slow")
    void the_terminal_keeps_one_progress_line_under_the_running_call() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal t = TerminalBuilder.builder().system(false).streams(new ByteArrayInputStream(new byte[0]), out)
            .type("xterm-256color").size(new Size(100, 24)).encoding(StandardCharsets.UTF_8).build();
        JLineRendererImpl r = new JLineRendererImpl(t);
        List<AgentEvent> events = run();
        events.subList(0, 6).forEach(r);
        List<String> live = r.liveLines().stream().map(AttributedString::toString).toList();
        assertThat(live).filteredOn(l -> l.contains("[sub-agent")).singleElement().asString()
            .isEqualTo("  [sub-agent sa-1a2b3c4d] turn 3: replied");
        assertThat(live.indexOf("  [sub-agent sa-1a2b3c4d] turn 3: replied")).isEqualTo(
            live.indexOf(live.stream().filter(l -> l.contains("spawn_agent")).findFirst().orElseThrow()) + 1);
        events.subList(6, events.size()).forEach(r);
        assertThat(r.liveLines()).hasSize(1);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("[sub-agent sa-1a2b3c4d] done (3 turns)");
        r.close();
    }
}
