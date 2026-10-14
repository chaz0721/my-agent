/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.StreamEvent;
import dev.pai.model.Usage;

/**
 * Prints agent events as plain lines: streamed text, one line per tool call and result, usage at the end.
 */
final class EventPrinter implements Consumer<AgentEvent> {

    private static final int SUMMARY_CHARS = 80;
    private static final double MILLIS_PER_SECOND = 1000.0d;

    private final PrintStream out;
    private final String start;
    private final String ok;
    private final String fail;
    private boolean midLine;

    EventPrinter(PrintStream out) {
        this.out = out;
        boolean unicode = out.charset().newEncoder().canEncode("▸✓✗");
        this.start = unicode ? "▸" : ">";
        this.ok = unicode ? "✓" : "ok";
        this.fail = unicode ? "✗" : "x";
    }

    @Override
    public void accept(AgentEvent event) {
        switch (event) {
            case AgentEvent.MessageUpdate u when u.event() instanceof StreamEvent.TextDelta t -> {
                out.print(t.delta());
                midLine = !t.delta().endsWith("\n");
                out.flush();
            }
            case AgentEvent.ToolExecutionStart s ->
                line(start + " " + s.call().name() + "(" + summarize(s.call().arguments().toString()) + ")");
            case AgentEvent.ToolExecutionEnd e -> line("  " + (e.result().isError() ? fail : ok) + " "
                + seconds(e.took()) + (e.result().isError() ? " " + summarize(firstText(e.result())) : ""));
            case AgentEvent.Error e -> line("error: " + e.message());
            case AgentEvent.AgentEnd e -> line(usage(e));
            default -> {
                // other events are not shown in the line REPL
            }
        }
    }

    private void line(String s) {
        if (midLine) {
            out.println();
            midLine = false;
        }
        out.println(s);
        out.flush();
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

    private static String seconds(Duration d) {
        return String.format(Locale.ROOT, "%.1fs", d.toMillis() / MILLIS_PER_SECOND);
    }

    private static String firstText(ToolResult r) {
        return r.content().stream().filter(ContentBlock.Text.class::isInstance).map(b -> ((ContentBlock.Text)b).text())
            .findFirst().orElse("");
    }

    static String summarize(String s) {
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= SUMMARY_CHARS ? flat : flat.substring(0, SUMMARY_CHARS - 3) + "...";
    }
}
