/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import dev.pai.core.loop.AgentEvent;
import dev.pai.model.AssistantMessage;
import dev.pai.model.StreamEvent;
import dev.pai.model.Usage;

/**
 * The coloured view of agent events: assistant text as {@link Markdown}, tool calls as {@link ToolCard}s (running ones
 * in the live area, each finished one printed whole when it ends, so parallel calls never interleave), reasoning as up
 * to three faint live lines, and a faint {@code usage in=… out=… (N.Ns)} line after each run. The last
 * {@value #KEPT_CARDS} finished cards are kept for {@code /expand}.
 */
final class RichView implements Consumer<AgentEvent> {

    /** Finished cards kept for {@code /expand}. */
    static final int KEPT_CARDS = 50;
    private static final double NANOS_PER_SECOND = 1e9;

    /**
     * Where the view draws.
     */
    interface Screen {

        /** Prints lines to the transcript, above the live area. */
        void print(List<AttributedString> lines);

        /** Redraws the live area. */
        void redraw();

        /** Terminal columns. */
        int width();
    }

    private final Screen screen;
    private final Theme theme;
    private final Symbols symbols;
    private final int fold;
    private final LongSupplier clock;
    private final Markdown markdown;
    private final Map<String, ToolCard> running = new LinkedHashMap<>();
    private final Deque<ToolCard> finished = new ArrayDeque<>();
    private final Deque<String> thinking = new ArrayDeque<>();
    private final StringBuilder thinkingLine = new StringBuilder();
    private int cards;
    private long busySince;

    /**
     * Creates the view.
     *
     * @param screen where to draw
     * @param theme the colours
     * @param symbols the glyphs
     * @param fold body lines of a card shown by default; 0 shows all
     * @param clock {@link System#nanoTime} or a test clock
     */
    RichView(Screen screen, Theme theme, Symbols symbols, int fold, LongSupplier clock) {
        this.screen = screen;
        this.theme = theme;
        this.symbols = symbols;
        this.fold = fold;
        this.clock = clock;
        this.markdown = new Markdown(theme, symbols, screen::width);
    }

    @Override
    public void accept(AgentEvent event) {
        switch (event) {
            case AgentEvent.AgentStart s -> busySince = nonZero(clock.getAsLong());
            case AgentEvent.MessageUpdate u when u.event() instanceof StreamEvent.TextDelta t -> text(t.delta());
            case AgentEvent.MessageUpdate u when u.event() instanceof StreamEvent.ThinkingDelta t ->
                thinking(t.delta());
            case AgentEvent.MessageEnd e when e.message() instanceof AssistantMessage -> flush();
            case AgentEvent.ToolExecutionStart s -> {
                flush();
                clearThinking();
                running.put(s.call().id(), new ToolCard(++cards, s.call()));
                screen.redraw();
            }
            case AgentEvent.ToolExecutionUpdate u when running.containsKey(u.toolCallId())
                && running.get(u.toolCallId()).isSubagent() -> EventView.turnLine(u.partial()).ifPresent(p -> {
                    running.get(u.toolCallId()).progress(p);
                    screen.redraw();
                });
            case AgentEvent.ToolExecutionEnd e -> {
                ToolCard card = running.remove(e.call().id());
                if (card == null) {
                    card = new ToolCard(++cards, e.call());
                }
                card.finish(e.result(), e.took());
                keep(card);
                screen.print(card.finished(theme, symbols, fold, screen.width()));
                screen.redraw();
            }
            case AgentEvent.ContextCompacted c when c.kind() == AgentEvent.ContextCompacted.KindEnum.MICRO -> {
                // shown only through the ctx% of the status bar
            }
            case AgentEvent.ContextCompacted c -> line(
                "[context compacted: " + c.messagesBefore() + " -> " + c.messagesAfter() + " messages]", theme.faint());
            case AgentEvent.Error e -> {
                flush();
                line("error: " + TerminalInteractionImpl.visible(e.message()), theme.error());
            }
            case AgentEvent.AgentEnd e -> {
                flush();
                clearThinking();
                Usage u = EventView.total(e);
                String secs = busySince == 0 ? ""
                    : String.format(Locale.ROOT, " (%.1fs)", (clock.getAsLong() - busySince) / NANOS_PER_SECOND);
                line("usage in=" + u.input() + " out=" + u.output()
                    + (u.cacheRead() > 0 ? " cacheRead=" + u.cacheRead() : "") + secs, theme.faint());
                busySince = 0;
                screen.redraw();
            }
            default -> {
                // turn boundaries and other messages need no output
            }
        }
    }

    /**
     * Lines of the live area above the status bar.
     *
     * @param width terminal columns
     * @param frame spinner frame
     * @return reasoning, the unfinished text line and the running cards
     */
    List<AttributedString> live(int width, String frame) {
        List<AttributedString> out = new ArrayList<>();
        thinking.forEach(t -> out.add(Theme.styled(Cols.cut(t, width), theme.faint())));
        if (!thinkingLine.isEmpty()) {
            out.add(Theme.styled(Cols.cut(Cols.clean(thinkingLine.toString()), width), theme.faint()));
        }
        String pending = markdown.pending();
        if (!pending.isEmpty()) {
            String tail = pending.length() > width * 2 ? pending.substring(pending.length() - width * 2) : pending;
            out.add(Theme.styled(Cols.cut(tail, width), theme.faint()));
        }
        running.values().forEach(c -> out.addAll(c.running(theme, frame, width)));
        return out;
    }

    /** When the current run started ({@link System#nanoTime}), 0 when idle. */
    long busySince() {
        return busySince;
    }

    /** Running sub-agent calls. */
    int subagents() {
        return (int)running.values().stream().filter(ToolCard::isSubagent).count();
    }

    /** Ends the run state before a line is read: nothing is running while the user types. */
    void idle() {
        flush();
        running.clear();
        clearThinking();
        busySince = 0;
    }

    /**
     * Shows a finished card in full.
     *
     * @param args empty for the latest card, else its number
     * @return what to print
     */
    List<AttributedString> expand(String args) {
        Optional<ToolCard> card;
        if (args.isBlank()) {
            card = Optional.ofNullable(finished.peekLast());
        } else {
            int n;
            try {
                n = Integer.parseInt(args.strip());
            } catch (NumberFormatException e) {
                return List.of(Theme.styled("usage: /expand [card number]", theme.warn()));
            }
            card = finished.stream().filter(c -> c.number() == n).findFirst();
        }
        return card.map(c -> c.expanded(theme, symbols, screen.width())).orElseGet(() -> List
            .of(Theme.styled("/expand: no such tool call (kept: the last " + KEPT_CARDS + ")", theme.warn())));
    }

    private void keep(ToolCard card) {
        finished.addLast(card);
        while (finished.size() > KEPT_CARDS) {
            finished.removeFirst();
        }
    }

    private void text(String delta) {
        if (!thinking.isEmpty() || !thinkingLine.isEmpty()) {
            clearThinking();
        }
        List<AttributedString> lines = markdown.feed(delta);
        if (!lines.isEmpty()) {
            screen.print(lines);
        }
        screen.redraw();
    }

    /** Prints what the Markdown renderer still holds. */
    void flush() {
        List<AttributedString> rest = markdown.finish();
        if (!rest.isEmpty()) {
            screen.print(rest);
        }
    }

    private void thinking(String delta) {
        for (char c : delta.toCharArray()) {
            if (c == '\n') {
                pushThinking();
            } else if (c != '\r') {
                thinkingLine.append(c);
            }
        }
        if (Cols.width(thinkingLine.toString()) >= Math.max(1, screen.width())) {
            pushThinking();
        }
        screen.redraw();
    }

    private void pushThinking() {
        if (!thinkingLine.isEmpty()) {
            thinking.addLast(Cols.clean(thinkingLine.toString()));
            thinkingLine.setLength(0);
        }
        while (thinking.size() > JLineRendererImpl.THINKING_LINES - 1) {
            thinking.removeFirst();
        }
    }

    private void clearThinking() {
        thinking.clear();
        thinkingLine.setLength(0);
    }

    private void line(String text, AttributedStyle style) {
        screen.print(List.of(Theme.styled(text, style)));
    }

    private static long nonZero(long nanos) {
        return nanos == 0 ? 1 : nanos;
    }
}
