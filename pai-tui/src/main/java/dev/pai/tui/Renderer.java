/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.IOException;
import java.util.function.Consumer;

import dev.pai.core.loop.AgentEvent;

/**
 * Shows agent events and reads user input. Implementations hold no business logic; everything they show comes from
 * {@link AgentEvent}s, {@link #println} and {@link #status}.
 */
public interface Renderer extends Consumer<AgentEvent>, AutoCloseable {

    /**
     * Reads one line; blocks.
     *
     * @param prompt the prompt
     * @return the line
     * @throws IOException on input failure
     * @throws org.jline.reader.UserInterruptException on Ctrl+C
     * @throws org.jline.reader.EndOfFileException on Ctrl+D or end of input
     */
    String readLine(String prompt) throws IOException;

    /**
     * Prints a line, above the input line when one is being edited.
     *
     * @param text the text
     */
    void println(String text);

    /**
     * Updates the bottom status line.
     *
     * @param s the status
     */
    void status(StatusLine s);

    /**
     * Sets what Ctrl+C does while no line is being read (the agent is working).
     *
     * @param action the action, run on a signal thread
     */
    void onInterrupt(Runnable action);

    /**
     * Restores the terminal.
     */
    @Override
    void close();
}
