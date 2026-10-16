/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;

import org.jline.reader.EndOfFileException;

import dev.pai.core.loop.AgentEvent;

import sun.misc.Signal;

/**
 * Line-oriented renderer for pipes, dumb terminals and tests: streamed text, one line per tool start and end and one
 * per sub-agent progress update; no reasoning, no status line.
 */
public final class PlainRendererImpl implements Renderer {

    private final BufferedReader in;
    private final PrintStream out;
    private final EventView view;
    private boolean midLine;
    private boolean afterPrompt;

    /**
     * Creates the renderer.
     *
     * @param in input
     * @param inputCharset charset of the input
     * @param out output
     */
    public PlainRendererImpl(InputStream in, Charset inputCharset, PrintStream out) {
        this.in = new BufferedReader(new InputStreamReader(in, inputCharset));
        this.out = out;
        this.view = new EventView(new PlainSurface(), Symbols.forCharset(out.charset()));
    }

    @Override
    public synchronized void accept(AgentEvent event) {
        view.accept(event);
    }

    @Override
    public String readLine(String prompt) throws IOException {
        synchronized (this) {
            endLine();
            out.print(prompt);
            out.flush();
            afterPrompt = true;
        }
        String line = in.readLine();
        if (line == null) {
            throw new EndOfFileException();
        }
        return line;
    }

    @Override
    public synchronized void println(String text) {
        endLine();
        out.println(text);
        out.flush();
    }

    @Override
    public void status(StatusLine s) {
        // a plain stream has no status line
    }

    @Override
    public void onInterrupt(Runnable action) {
        try {
            Signal.handle(new Signal("INT"), sig -> action.run());
        } catch (IllegalArgumentException e) {
            // INT cannot be handled on this platform: the default (exit) applies
        }
    }

    @Override
    public synchronized void close() {
        endLine();
        out.flush();
    }

    /** Starts a fresh line after streamed text or a prompt whose input was not echoed (piped input). */
    private void endLine() {
        if (midLine || afterPrompt) {
            out.println();
            midLine = false;
            afterPrompt = false;
        }
    }

    /**
     * Writes everything straight to the stream.
     */
    private final class PlainSurface implements EventView.Surface {
        @Override
        public void text(String delta) {
            if (afterPrompt) {
                endLine();
            }
            out.print(delta);
            out.flush();
            midLine = !delta.isEmpty() ? !delta.endsWith("\n") : midLine;
        }

        @Override
        public void line(String line) {
            println(line);
        }

        @Override
        public void thinking(String delta) {
            // reasoning is not part of the transcript
        }

        @Override
        public void toolStarted(String callId, String line) {
            println(line);
        }

        @Override
        public void toolFinished(String callId, String line) {
            println(line);
        }

        @Override
        public void progress(String callId, String line) {
            println(line);
        }

        @Override
        public void phase(String phase) {
            // no status line
        }
    }
}
