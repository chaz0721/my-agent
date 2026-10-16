/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;

/**
 * The status bar under the input line: {@code [AUTO_READ] deepseek-chat · ctx 23% · 3/5 tasks · 2 sub-agents · ⠋ 4.2s}.
 * The approval mode is not part of {@link StatusLine} (the approval extension owns it), so it is read from the lines
 * that extension prints ({@code approvals: mode auto-read}, {@code plan mode on}) and left out until one has been seen.
 */
final class BottomBar {

    /** Terminal width from which the task list stays visible beside the counter. */
    static final int WIDE = 120;
    private static final Pattern MODE = Pattern.compile("approvals: mode ([a-z-]+)");
    private static final Pattern TASKS = Pattern.compile("tasks (\\d+/\\d+)");
    private static final double NANOS_PER_SECOND = 1e9;

    private BottomBar() {}

    /**
     * What the bar shows besides the {@link StatusLine}.
     *
     * @param mode approval mode, empty while unknown
     * @param tasks task progress such as {@code 3/5}, empty without a task list
     * @param subagents running sub-agent calls
     * @param busySinceNanos when the current run started ({@link System#nanoTime}); 0 when idle
     * @param frame spinner frame
     */
    record Extras(String mode, String tasks, int subagents, long busySinceNanos, String frame) {
    }

    /**
     * Renders the bar.
     *
     * @param s the status
     * @param x the extras
     * @param t the colours
     * @param sym the glyphs
     * @param width terminal columns
     * @param nowNanos the current {@link System#nanoTime}
     * @return the line, cut to the width
     */
    static AttributedString render(StatusLine s, Extras x, Theme t, Symbols sym, int width, long nowNanos) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        if (!x.mode().isEmpty()) {
            sb.append("[" + x.mode().toUpperCase(Locale.ROOT).replace('-', '_') + "] ",
                "plan".equals(x.mode()) ? t.warn() : t.tool());
        }
        String model = s.model().contains("/") ? s.model().substring(s.model().indexOf('/') + 1) : s.model();
        List<String> parts = new ArrayList<>();
        parts.add(model);
        parts.add("ctx " + s.ctxPercent() + "%");
        if (!x.tasks().isEmpty()) {
            parts.add(x.tasks() + " tasks");
        }
        if (x.subagents() > 0) {
            parts.add(x.subagents() + (x.subagents() == 1 ? " sub-agent" : " sub-agents"));
        }
        sb.append(String.join(sym.dot(), parts), t.faint());
        if (x.busySinceNanos() != 0) {
            double secs = (nowNanos - x.busySinceNanos()) / NANOS_PER_SECOND;
            sb.append(sym.dot(), t.faint()).append(x.frame() + " ", t.tool())
                .append(String.format(Locale.ROOT, "%.1fs", secs), t.faint());
        }
        return Cols.fit(sb.toAttributedString(), Math.max(1, width));
    }

    /**
     * Finds the approval mode in a printed text.
     *
     * @param printed a line printed to the transcript
     * @return the mode, for example {@code auto-read}
     */
    static Optional<String> modeIn(String printed) {
        if (printed.startsWith("plan mode on")) {
            return Optional.of("plan");
        }
        Matcher m = MODE.matcher(printed);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * Finds the progress in the first line of the task list.
     *
     * @param sidebar the task list lines
     * @return for example {@code 3/5}; empty without a list
     */
    static String tasks(List<String> sidebar) {
        if (sidebar.isEmpty()) {
            return "";
        }
        Matcher m = TASKS.matcher(sidebar.get(0));
        return m.lookingAt() ? m.group(1) : "";
    }
}
