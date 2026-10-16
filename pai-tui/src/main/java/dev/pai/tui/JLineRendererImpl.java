/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;
import org.jline.utils.Status;

import dev.pai.core.loop.AgentEvent;
import dev.pai.model.Usage;

/**
 * Terminal renderer on JLine: line editing with history, streamed text written above a JLine {@link Status} area that
 * holds up to three faint lines of live reasoning, the sidebar (task list), the running tool calls (a sub-agent call
 * with its one progress line below it) and the status line. Shift+Tab runs the action given to {@link #onModeCycle};
 * what it prints goes above the input line. Without scroll-region support the live area is skipped and tool starts are
 * printed as lines.
 */
public final class JLineRendererImpl implements Renderer {

    /** Lines of reasoning kept visible while the model thinks. */
    public static final int THINKING_LINES = 3;

    /** What terminals send for Shift+Tab (back tab). */
    static final String SHIFT_TAB = "\033[Z";
    private static final String MODE_CYCLE_WIDGET = "pai-mode-cycle";
    private static final AttributedStyle FAINT = AttributedStyle.DEFAULT.faint();
    private static final AttributedStyle STATUS_STYLE = AttributedStyle.DEFAULT.inverse();

    private final Terminal terminal;
    private final LineReader reader;
    private final Status status;
    private final boolean live;
    private final Symbols symbols;
    private final EventView view;
    private final Deque<String> thinking = new ArrayDeque<>();
    private final Map<String, String> running = new LinkedHashMap<>();
    private final Map<String, String> progress = new LinkedHashMap<>();
    private final StringBuilder thinkingLine = new StringBuilder();
    private List<String> sidebar = List.of();
    private volatile boolean inWidget;
    private StatusLine statusLine = new StatusLine("", 0, Usage.ZERO, "", StatusLine.IDLE);
    private boolean midLine;

    /**
     * Wraps a terminal.
     *
     * @param terminal the terminal; closed by {@link #close()}
     */
    public JLineRendererImpl(Terminal terminal) {
        this.terminal = terminal;
        this.reader = LineReaderBuilder.builder().terminal(terminal).appName("pai").build();
        this.live = supportsStatus(terminal);
        this.status = live ? Status.getStatus(terminal) : null;
        this.symbols = Symbols.forCharset(terminal.outputEncoding());
        this.view = new EventView(new LiveSurface(), symbols);
        terminal.handle(Terminal.Signal.WINCH, sig -> resized());
    }

    /**
     * Opens the system terminal. On Windows and JDK 21 the JNI terminal provider needs
     * {@code --enable-native-access=ALL-UNNAMED}; without a native provider this fails instead of degrading to a dumb
     * terminal, so that the caller can fall back to {@link PlainRendererImpl}.
     *
     * @return the renderer
     * @throws UncheckedIOException when no system terminal can be opened
     */
    public static JLineRendererImpl system() {
        try {
            // a non-default handler makes JLine route native signals to Terminal.handle (see onInterrupt)
            return new JLineRendererImpl(TerminalBuilder.builder().system(true).name("pai").dumb(false)
                .signalHandler(Terminal.SignalHandler.SIG_IGN).build());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open the terminal", e);
        } catch (IllegalStateException e) {
            throw new UncheckedIOException(new IOException(e.getMessage(), e));
        }
    }

    @Override
    public synchronized void accept(AgentEvent event) {
        view.accept(event);
    }

    @Override
    public String readLine(String prompt) {
        synchronized (this) {
            endLine();
            running.clear();
            progress.clear();
            clearThinking();
            redraw();
        }
        return reader.readLine(prompt);
    }

    @Override
    public synchronized void println(String text) {
        if (inWidget || reader.isReading()) {
            reader.printAbove(text);
            return;
        }
        endLine();
        writer().print(text + "\n");
        writer().flush();
    }

    @Override
    public synchronized void status(StatusLine s) {
        statusLine = s;
        redraw();
    }

    @Override
    public void onInterrupt(Runnable action) {
        terminal.handle(Terminal.Signal.INT, sig -> action.run());
    }

    @Override
    public synchronized void sidebar(List<String> lines) {
        sidebar = List.copyOf(lines);
        redraw();
    }

    @Override
    public void onModeCycle(Runnable action) {
        reader.getWidgets().put(MODE_CYCLE_WIDGET, () -> {
            inWidget = true;
            try {
                action.run();
            } finally {
                inWidget = false;
            }
            return true;
        });
        for (String map : List.of(LineReader.EMACS, LineReader.VIINS)) {
            reader.getKeyMaps().get(map).bind(new Reference(MODE_CYCLE_WIDGET), SHIFT_TAB);
        }
    }

    @Override
    public synchronized void close() {
        endLine();
        if (status != null) {
            status.close();
        }
        try {
            terminal.close();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot restore the terminal", e);
        }
    }

    /** Lines of the live area for the current state, cut to the terminal width. */
    synchronized List<AttributedString> liveLines() {
        int width = Math.max(1, terminal.getWidth());
        List<AttributedString> lines = new ArrayList<>();
        for (String t : thinking) {
            lines.add(new AttributedString(ToolCallSummaries.cut(t, width), FAINT));
        }
        if (!thinkingLine.isEmpty()) {
            lines.add(new AttributedString(ToolCallSummaries.cut(thinkingLine.toString(), width), FAINT));
        }
        for (String t : sidebar) {
            lines.add(new AttributedString(ToolCallSummaries.cut(t, width), FAINT));
        }
        for (Map.Entry<String, String> r : running.entrySet()) {
            lines.add(new AttributedString(ToolCallSummaries.cut(r.getValue(), width)));
            String p = progress.get(r.getKey());
            if (p != null) {
                lines.add(new AttributedString(ToolCallSummaries.cut("  " + p, width), FAINT));
            }
        }
        lines.add(new AttributedString(ToolCallSummaries.cut(statusLine.render(symbols.dot()), width), STATUS_STYLE));
        return lines;
    }

    private void redraw() {
        if (live) {
            status.update(liveLines());
        }
    }

    private synchronized void resized() {
        if (live) {
            status.resize();
            redraw();
        }
    }

    private void clearThinking() {
        thinking.clear();
        thinkingLine.setLength(0);
    }

    private void endLine() {
        if (midLine) {
            writer().print("\n");
            writer().flush();
            midLine = false;
        }
    }

    private PrintWriter writer() {
        return terminal.writer();
    }

    private static boolean supportsStatus(Terminal t) {
        return t.getStringCapability(InfoCmp.Capability.change_scroll_region) != null
            && t.getStringCapability(InfoCmp.Capability.save_cursor) != null
            && t.getStringCapability(InfoCmp.Capability.restore_cursor) != null
            && t.getStringCapability(InfoCmp.Capability.cursor_address) != null;
    }

    /**
     * Streams text into the transcript and keeps reasoning and running tools in the live area.
     */
    private final class LiveSurface implements EventView.Surface {
        @Override
        public void text(String delta) {
            if (delta.isEmpty()) {
                return;
            }
            if (!thinking.isEmpty() || !thinkingLine.isEmpty()) {
                clearThinking();
                redraw();
            }
            writer().print(delta);
            writer().flush();
            midLine = !delta.endsWith("\n");
        }

        @Override
        public void line(String line) {
            println(line);
        }

        @Override
        public void thinking(String delta) {
            if (!live) {
                return;
            }
            for (char c : delta.toCharArray()) {
                if (c == '\n') {
                    pushThinking();
                } else if (c != '\r') {
                    thinkingLine.append(c);
                }
            }
            if (thinkingLine.length() >= Math.max(1, terminal.getWidth())) {
                pushThinking();
            }
            redraw();
        }

        private void pushThinking() {
            if (!thinkingLine.isEmpty()) {
                thinking.addLast(thinkingLine.toString());
                thinkingLine.setLength(0);
            }
            while (thinking.size() > THINKING_LINES - 1) {
                thinking.removeFirst();
            }
        }

        @Override
        public void toolStarted(String callId, String line) {
            if (live) {
                running.put(callId, line);
                redraw();
            } else {
                println(line);
            }
        }

        @Override
        public void progress(String callId, String line) {
            if (live) {
                progress.put(callId, line);
                redraw();
            } else {
                println(line);
            }
        }

        @Override
        public void toolFinished(String callId, String line) {
            running.remove(callId);
            progress.remove(callId);
            println(line);
            redraw();
        }

        @Override
        public void phase(String phase) {
            statusLine = statusLine.withPhase(phase);
            if (StatusLine.TOOL.equals(phase)) {
                clearThinking();
            }
            redraw();
        }
    }
}
