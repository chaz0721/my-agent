/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;
import org.jline.utils.Status;

import dev.pai.core.loop.AgentEvent;
import dev.pai.model.Usage;

/**
 * Terminal renderer on JLine: line editing with history, output written above a JLine {@link Status} area at the bottom
 * of the screen (the terminal's own scrollback is kept; no alternate screen). Shift+Tab runs the action given to
 * {@link #onModeCycle}; what it prints goes above the input line.
 * <p>
 * Without colour ({@link Theme#NONE}) the behaviour is M8.5's ({@link MonoSurface}). With a colour theme and a live
 * area events go through {@link RichView}: Markdown, tool cards with folded output and {@code /expand [n]} (handled
 * here, before the line reaches the REPL), a status bar with mode, tasks, sub-agents and a spinner, and {@link Picker}
 * boxes for questions. Without scroll-region support there is no live area and tool starts are printed as lines.
 */
public final class JLineRendererImpl implements Renderer, Chooser {

    /** Lines of reasoning kept visible while the model thinks. */
    public static final int THINKING_LINES = 3;

    /** What terminals send for Shift+Tab (back tab). */
    static final String SHIFT_TAB = "\033[Z";
    private static final String MODE_CYCLE_WIDGET = "pai-mode-cycle";
    private static final Pattern EXPAND = Pattern.compile("/expand(?:\\s+(.*))?");
    private static final long FRAME_MILLIS = 120L;
    /** What a cancelled choice box answers when it is one of the choices: Esc means no. */
    private static final String CANCEL_ANSWER = "n";
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final Terminal terminal;
    private final LineReader reader;
    private final Status status;
    private final boolean live;
    private final Symbols symbols;
    private final Theme theme;
    private final Consumer<AgentEvent> view;
    /** The uncoloured surface; null when {@link #rich} is used. */
    private final MonoSurface mono;
    /** The coloured view; null without colour or live area. */
    private final RichView rich;
    private final ScheduledExecutorService ticker;
    private List<String> sidebar = List.of();
    private volatile boolean inWidget;
    private StatusLine statusLine = new StatusLine("", 0, Usage.ZERO, "", StatusLine.IDLE);
    private boolean midLine;
    private Picker picker;
    private String mode = "";

    /**
     * Wraps a terminal, without colour (the M8.5 behaviour).
     *
     * @param terminal the terminal; closed by {@link #close()}
     */
    public JLineRendererImpl(Terminal terminal) {
        this(terminal, Theme.NONE, ToolCard.FOLD_LINES);
    }

    /**
     * Wraps a terminal.
     *
     * @param terminal the terminal; closed by {@link #close()}
     * @param theme the colours; {@link Theme#NONE} keeps the uncoloured M8.5 behaviour
     * @param fold body lines of a tool card shown by default; 0 shows all
     */
    public JLineRendererImpl(Terminal terminal, Theme theme, int fold) {
        this.terminal = terminal;
        this.reader = LineReaderBuilder.builder().terminal(terminal).appName("pai").build();
        this.live = supportsStatus(terminal);
        this.status = live ? Status.getStatus(terminal) : null;
        this.symbols = Symbols.forCharset(terminal.outputEncoding());
        this.theme = symbols.unicode() ? theme : theme.withSpinner(Theme.ASCII_SPINNER);
        boolean colour = this.theme.color() && live;
        this.rich = colour ? new RichView(new RichScreen(), this.theme, symbols, fold, System::nanoTime) : null;
        this.mono = colour ? null : new MonoSurface(new MonoHost());
        this.view = colour ? rich : new EventView(mono, symbols);
        this.ticker = colour ? new ScheduledThreadPoolExecutor(1, r -> {
            Thread th = new Thread(r, "pai-spinner");
            th.setDaemon(true);
            return th;
        }) : null;
        if (ticker != null) {
            ticker.scheduleAtFixedRate(this::tick, FRAME_MILLIS, FRAME_MILLIS, TimeUnit.MILLISECONDS);
        }
        terminal.handle(Terminal.Signal.WINCH, sig -> resized());
    }

    /**
     * Opens the system terminal, with the theme and folding the environment asks for ({@link Theme#detect},
     * {@link RendererFactory#fold}). On Windows and JDK 21 the JNI terminal provider needs
     * {@code --enable-native-access=ALL-UNNAMED}; without a native provider this fails instead of degrading to a dumb
     * terminal, so that the caller can fall back to {@link PlainRendererImpl}.
     *
     * @return the renderer
     * @throws UncheckedIOException when no system terminal can be opened
     */
    public static JLineRendererImpl system() {
        try {
            // a non-default handler makes JLine route native signals to Terminal.handle (see onInterrupt)
            Terminal t = TerminalBuilder.builder().system(true).name("pai").dumb(false)
                .signalHandler(Terminal.SignalHandler.SIG_IGN).build();
            Map<String, String> env = System.getenv();
            boolean windows = System.getProperty("os.name", "").startsWith("Windows");
            Theme theme =
                Theme.detect(env, t.getType(), t.getNumericCapability(InfoCmp.Capability.max_colors), windows);
            return new JLineRendererImpl(t, theme, RendererFactory.fold(env));
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
        while (true) {
            synchronized (this) {
                endLine();
                if (rich != null) {
                    rich.idle();
                } else {
                    mono.reset();
                }
                redraw();
            }
            String line = reader.readLine(prompt);
            Matcher m = EXPAND.matcher(line.strip());
            if (rich == null || !m.matches()) {
                return line;
            }
            synchronized (this) {
                print(rich.expand(m.group(1) == null ? "" : m.group(1)));
            }
        }
    }

    @Override
    public synchronized void println(String text) {
        if (rich != null) {
            BottomBar.modeIn(text).ifPresent(m -> mode = m);
            rich.flush();
            print(List.of(Theme.styled(text, text.startsWith("! ") ? theme.warn() : AttributedStyle.DEFAULT)));
            redraw();
            return;
        }
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

    /**
     * Asks with a {@link Picker} box drawn in the live area; the question body is printed above it (a diff coloured),
     * the box is replaced by one faint line with the answer when done. Keys are read in raw mode. Esc or Ctrl+C answers
     * {@code n} when that is a choice (the approval, trust and memory questions), else no answer.
     */
    @Override
    public Optional<String> choose(String prompt, List<String> choices) {
        if (rich == null || choices.isEmpty()) {
            return Optional.empty();
        }
        if (reader.isReading()) {
            throw new IllegalStateException("the terminal is reading a line");
        }
        Picker p = Picker.of(prompt, choices);
        synchronized (this) {
            rich.flush();
            if (!p.body().isBlank()) {
                print(DiffView.question(p.body(), theme));
            }
            picker = p;
            redraw();
        }
        Optional<String> answer = Optional.empty();
        Attributes saved = terminal.enterRawMode();
        try {
            answer = p.run(ms -> terminal.reader().read(ms), this::redrawNow);
        } catch (IOException e) {
            answer = Optional.empty();
        } finally {
            terminal.setAttributes(saved);
        }
        String result = answer.orElse(choices.contains(CANCEL_ANSWER) ? CANCEL_ANSWER : "");
        synchronized (this) {
            picker = null;
            String shown = p.options().stream().filter(o -> o.key().equals(result)).findFirst()
                .map(o -> o.key() + " (" + o.label() + ")").orElse("");
            String line = answer.isPresent() ? shown : ("cancelled " + shown).strip();
            print(List.of(Theme.styled(p.title() + " " + symbols.pointer() + " " + line, theme.faint())));
            redraw();
        }
        return Optional.of(result);
    }

    @Override
    public synchronized void close() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
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
        return rich != null ? richLines(width) : mono.lines(width, sidebar, statusLine.render(symbols.dot()));
    }

    /**
     * The coloured live area: reasoning, unfinished text, running cards, the task list on wide terminals, an open
     * choice box and the status bar; the oldest lines give way when it would take more than half the screen.
     */
    private List<AttributedString> richLines(int width) {
        String frames = theme.spinner();
        long now = System.nanoTime();
        String frame = String.valueOf(frames.charAt((int)(now / NANOS_PER_MILLI / FRAME_MILLIS % frames.length())));
        List<AttributedString> top = new ArrayList<>(rich.live(width, frame));
        if (width >= BottomBar.WIDE) {
            sidebar.forEach(l -> top.add(Theme.styled(Cols.cut(l, width), theme.faint())));
        }
        List<AttributedString> box = picker == null ? List.of() : picker.lines(theme, symbols, width);
        int room = Math.max(0, Math.max(2, terminal.getHeight() / 2) - box.size() - 1);
        List<AttributedString> lines = new ArrayList<>(top.subList(Math.max(0, top.size() - room), top.size()));
        lines.addAll(box);
        BottomBar.Extras x =
            new BottomBar.Extras(mode, BottomBar.tasks(sidebar), rich.subagents(), rich.busySince(), frame);
        lines.add(BottomBar.render(statusLine, x, theme, symbols, width, now));
        return lines;
    }

    private synchronized void tick() {
        if (rich.busySince() != 0 && !reader.isReading()) {
            redraw();
        }
    }

    private synchronized void redrawNow() {
        redraw();
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

    /** Prints styled lines above the live area (above the input line while one is edited). */
    private void print(List<AttributedString> lines) {
        if (inWidget || reader.isReading()) {
            lines.forEach(reader::printAbove);
            return;
        }
        endLine();
        for (AttributedString l : lines) {
            writer().print(Cols.ansi(l) + "\n");
        }
        writer().flush();
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
     * Where the coloured view draws.
     */
    private final class RichScreen implements RichView.Screen {
        @Override
        public void print(List<AttributedString> lines) {
            JLineRendererImpl.this.print(lines);
        }

        @Override
        public void redraw() {
            JLineRendererImpl.this.redraw();
        }

        @Override
        public int width() {
            return Math.max(1, terminal.getWidth());
        }
    }

    /**
     * What the uncoloured surface writes through.
     */
    private final class MonoHost implements MonoSurface.Host {
        @Override
        public void write(String delta) {
            writer().print(delta);
            writer().flush();
            midLine = !delta.endsWith("\n");
        }

        @Override
        public void println(String line) {
            JLineRendererImpl.this.println(line);
        }

        @Override
        public void redraw() {
            JLineRendererImpl.this.redraw();
        }

        @Override
        public int width() {
            return Math.max(1, terminal.getWidth());
        }

        @Override
        public boolean live() {
            return live;
        }

        @Override
        public void phase(String phase) {
            statusLine = statusLine.withPhase(phase);
            redraw();
        }
    }
}
