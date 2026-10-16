/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.pai.core.loop.AgentEvent;
import dev.pai.model.AssistantMessage;
import dev.pai.model.Message;
import dev.pai.model.StreamEvent;
import dev.pai.model.Usage;

/**
 * Turns agent events into surface operations shared by both renderers: streamed text, live reasoning, one line per
 * finished tool call, notices and the usage line. Of the partial updates of running tools only those of
 * {@value #SPAWN_AGENT} are shown: its per-turn lines ({@code [sa-xxxx type] turn N: what it did}) become one progress
 * line {@code [sub-agent sa-xxxx] turn N: <summary>} that each update replaces, and a closing
 * {@code [sub-agent sa-xxxx] done (N turns)} when the call ends.
 */
final class EventView implements Consumer<AgentEvent> {

    private static final double MILLIS_PER_SECOND = 1000.0d;
    /** The tool whose partial updates are shown. */
    static final String SPAWN_AGENT = "spawn_agent";
    /** Longest summary in a progress line. */
    static final int MAX_SUMMARY_CHARS = 80;
    private static final Pattern TURN_LINE = Pattern.compile("\\[(\\S+) [^\\]]*\\] turn (\\d+): (.*)", Pattern.DOTALL);

    /**
     * Where the view draws.
     */
    interface Surface {

        /** Appends streamed assistant text to the transcript. */
        void text(String delta);

        /** Prints a complete line to the transcript, starting on a fresh line. */
        void line(String line);

        /** Appends reasoning text to the transient reasoning area. */
        void thinking(String delta);

        /** A tool call started; {@code line} describes it. */
        void toolStarted(String callId, String line);

        /** A tool call ended; {@code line} is its permanent transcript line. */
        void toolFinished(String callId, String line);

        /**
         * Shows the progress line of a running call, replacing the previous one of that call; it goes away when the
         * call finishes.
         */
        void progress(String callId, String line);

        /** The agent phase changed ({@link StatusLine#IDLE}, {@link StatusLine#THINKING}, {@link StatusLine#TOOL}). */
        void phase(String phase);
    }

    private final Surface surface;
    private final Symbols symbols;
    /** Running {@value #SPAWN_AGENT} calls by call id: the run id and the last turn seen (empty before the first). */
    private final Map<String, String[]> subagents = new HashMap<>();

    EventView(Surface surface, Symbols symbols) {
        this.surface = surface;
        this.symbols = symbols;
    }

    @Override
    public void accept(AgentEvent event) {
        switch (event) {
            case AgentEvent.MessageStart s when s.message() instanceof AssistantMessage ->
                surface.phase(StatusLine.THINKING);
            case AgentEvent.MessageUpdate u -> update(u.event());
            case AgentEvent.ToolExecutionStart s -> {
                if (SPAWN_AGENT.equals(s.call().name())) {
                    subagents.put(s.call().id(), new String[2]);
                }
                surface.phase(StatusLine.TOOL);
                surface.toolStarted(s.call().id(), symbols.start() + " " + ToolCallSummaries.describe(s.call()));
            }
            case AgentEvent.ToolExecutionUpdate u when subagents.containsKey(u.toolCallId()) -> progress(u);
            case AgentEvent.ToolExecutionEnd e -> {
                String[] run = subagents.remove(e.call().id());
                if (run != null && run[1] != null) {
                    surface.line("[sub-agent " + run[0] + "] done (" + run[1] + " turns)");
                }
                String mark = e.result().isError() ? symbols.fail() : symbols.ok();
                String outcome = ToolCallSummaries.outcome(e.call(), e.result()).map(o -> o + ", ").orElse("");
                surface.toolFinished(e.call().id(),
                    mark + " " + ToolCallSummaries.describe(e.call()) + " (" + outcome + seconds(e.took()) + ")");
            }
            case AgentEvent.ContextCompacted c when c.kind() == AgentEvent.ContextCompacted.KindEnum.MICRO -> {
                // shown only through the ctx% of the status line
            }
            case AgentEvent.ContextCompacted c ->
                surface.line("[context compacted: " + c.messagesBefore() + " -> " + c.messagesAfter() + " messages]");
            case AgentEvent.Error e -> surface.line("error: " + e.message());
            case AgentEvent.AgentEnd e -> {
                surface.line(usage(e));
                surface.phase(StatusLine.IDLE);
            }
            default -> {
                // turn boundaries and completed messages need no output
            }
        }
    }

    private void progress(AgentEvent.ToolExecutionUpdate u) {
        Matcher m = TURN_LINE.matcher(u.partial().strip());
        if (!m.matches()) {
            return;
        }
        String[] run = subagents.get(u.toolCallId());
        run[0] = m.group(1);
        run[1] = m.group(2);
        surface.progress(u.toolCallId(), "[sub-agent " + run[0] + "] turn " + run[1] + ": "
            + ToolCallSummaries.cut(m.group(3).strip(), MAX_SUMMARY_CHARS));
    }

    private void update(StreamEvent ev) {
        switch (ev) {
            case StreamEvent.TextDelta t -> surface.text(t.delta());
            case StreamEvent.ThinkingDelta t -> surface.thinking(t.delta());
            default -> {
                // tool call streaming is shown when execution starts
            }
        }
    }

    private static String usage(AgentEvent.AgentEnd e) {
        Usage total = Usage.ZERO;
        for (Message m : e.newMessages()) {
            if (m instanceof AssistantMessage a) {
                total = total.plus(a.usage());
            }
        }
        return "[usage in=" + total.input() + " out=" + total.output() + " cacheRead=" + total.cacheRead()
            + " cacheWrite=" + total.cacheWrite() + "]";
    }

    static String seconds(Duration d) {
        return String.format(Locale.ROOT, "%.2fs", d.toMillis() / MILLIS_PER_SECOND);
    }
}
