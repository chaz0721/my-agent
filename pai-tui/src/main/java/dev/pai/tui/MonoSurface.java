/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/**
 * The uncoloured (M8.5) live surface of the terminal renderer: streamed text written as it comes, up to three faint
 * lines of live reasoning, the sidebar, the running tool calls (a sub-agent call with its one progress line below it)
 * and an inverse status line. Without a live area tool starts and progress are printed as lines.
 */
final class MonoSurface implements EventView.Surface {

    private static final AttributedStyle FAINT = AttributedStyle.DEFAULT.faint();
    private static final AttributedStyle STATUS_STYLE = AttributedStyle.DEFAULT.inverse();

    /**
     * What the surface needs from the renderer.
     */
    interface Host {

        /** Writes streamed text to the transcript as it is. */
        void write(String delta);

        /** Prints a line to the transcript. */
        void println(String line);

        /** Redraws the live area. */
        void redraw();

        /** Terminal columns. */
        int width();

        /** Whether there is a live area. */
        boolean live();

        /** The agent phase changed. */
        void phase(String phase);
    }

    private final Host host;
    private final Deque<String> thinking = new ArrayDeque<>();
    private final Map<String, String> running = new LinkedHashMap<>();
    private final Map<String, String> progress = new LinkedHashMap<>();
    private final StringBuilder thinkingLine = new StringBuilder();

    MonoSurface(Host host) {
        this.host = host;
    }

    /** Forgets running calls and reasoning: nothing runs while a line is read. */
    void reset() {
        running.clear();
        progress.clear();
        clearThinking();
    }

    /**
     * Lines of the live area, cut to the width.
     *
     * @param width terminal columns
     * @param sidebar the sidebar lines
     * @param status the status line text
     * @return the lines, the status line last
     */
    List<AttributedString> lines(int width, List<String> sidebar, String status) {
        List<AttributedString> lines = new ArrayList<>();
        for (String t : thinking) {
            lines.add(new AttributedString(Cols.cut(t, width), FAINT));
        }
        if (!thinkingLine.isEmpty()) {
            lines.add(new AttributedString(Cols.cut(thinkingLine.toString(), width), FAINT));
        }
        for (String t : sidebar) {
            lines.add(new AttributedString(Cols.cut(t, width), FAINT));
        }
        for (Map.Entry<String, String> r : running.entrySet()) {
            lines.add(new AttributedString(Cols.cut(r.getValue(), width)));
            String p = progress.get(r.getKey());
            if (p != null) {
                lines.add(new AttributedString(Cols.cut("  " + p, width), FAINT));
            }
        }
        lines.add(new AttributedString(Cols.cut(status, width), STATUS_STYLE));
        return lines;
    }

    @Override
    public void text(String delta) {
        if (delta.isEmpty()) {
            return;
        }
        if (!thinking.isEmpty() || !thinkingLine.isEmpty()) {
            clearThinking();
            host.redraw();
        }
        host.write(delta);
    }

    @Override
    public void line(String line) {
        host.println(line);
    }

    @Override
    public void thinking(String delta) {
        if (!host.live()) {
            return;
        }
        for (char c : delta.toCharArray()) {
            if (c == '\n') {
                pushThinking();
            } else if (c != '\r') {
                thinkingLine.append(c);
            }
        }
        if (thinkingLine.length() >= host.width()) {
            pushThinking();
        }
        host.redraw();
    }

    private void pushThinking() {
        if (!thinkingLine.isEmpty()) {
            thinking.addLast(thinkingLine.toString());
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

    @Override
    public void toolStarted(String callId, String line) {
        if (host.live()) {
            running.put(callId, line);
            host.redraw();
        } else {
            host.println(line);
        }
    }

    @Override
    public void progress(String callId, String line) {
        if (host.live()) {
            progress.put(callId, line);
            host.redraw();
        } else {
            host.println(line);
        }
    }

    @Override
    public void toolFinished(String callId, String line) {
        running.remove(callId);
        progress.remove(callId);
        host.println(line);
        host.redraw();
    }

    @Override
    public void phase(String phase) {
        if (StatusLine.TOOL.equals(phase)) {
            clearThinking();
        }
        host.phase(phase);
    }
}
