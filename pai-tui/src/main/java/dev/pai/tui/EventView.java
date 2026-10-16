/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.StreamEvent;
import dev.pai.model.Usage;

/**
 * Turns agent events into surface operations shared by both renderers: streamed text, live reasoning, one line per
 * finished tool call, notices and the usage line. Of the partial updates of running tools only those of
 * {@value #SPAWN_AGENT} are shown: its per-turn lines ({@code [sa-xxxx type] turn N: what it did}) become one progress
 * line {@code [sub-agent sa-xxxx] turn N: <summary>} (control characters made visible) that each update replaces, and a
 * closing {@code [sub-agent sa-xxxx] done|aborted|failed (N turns)} when the call ends.
 */
final class EventView implements Consumer<AgentEvent> {

    private static final double MILLIS_PER_SECOND = 1000.0d;
    /** The tool whose partial updates are shown. */
    static final String SPAWN_AGENT = "spawn_agent";
    /** Longest summary in a progress line. */
    static final int MAX_SUMMARY_CHARS = 80;
    private static final String TURN_MARK = "] turn ";

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
                    surface.line("[sub-agent " + run[0] + "] " + ending(e.result()) + " (" + run[1] + " turns)");
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

    /**
     * Folds one per-turn line of a sub-agent ({@code [<run id> <type>] turn <n>: <summary>}, the format of
     * {@code SubagentExtension.PROGRESS_LINE}; tui cannot depend on the extension, so the format is matched here by its
     * fixed parts) into its progress line; other partial text is ignored.
     */
    private void progress(AgentEvent.ToolExecutionUpdate u) {
        String p = u.partial().strip();
        int close = p.indexOf(TURN_MARK);
        int space = p.indexOf(' ');
        if (!p.startsWith("[") || close < 0 || space < 0 || space > close) {
            return;
        }
        String rest = p.substring(close + TURN_MARK.length());
        int colon = rest.indexOf(": ");
        if (colon <= 0 || !rest.substring(0, colon).chars().allMatch(Character::isDigit)) {
            return;
        }
        String[] run = subagents.get(u.toolCallId());
        run[0] = TerminalInteractionImpl.visible(p.substring(1, space));
        run[1] = rest.substring(0, colon);
        surface.progress(u.toolCallId(), "[sub-agent " + run[0] + "] turn " + run[1] + ": " + ToolCallSummaries
            .cut(TerminalInteractionImpl.visible(rest.substring(colon + 2).strip()), MAX_SUMMARY_CHARS));
    }

    /** How a sub-agent call ended: {@code aborted} (cancelled), {@code failed} (any other error) or {@code done}. */
    private static String ending(ToolResult r) {
        if (!r.isError()) {
            return "done";
        }
        boolean aborted =
            r.content().stream().anyMatch(b -> b instanceof ContentBlock.Text t && t.text().contains("): aborted,"));
        return aborted ? "aborted" : "failed";
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
