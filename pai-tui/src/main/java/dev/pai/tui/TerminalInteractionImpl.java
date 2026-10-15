/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

import dev.pai.core.ext.UserInteraction;

/**
 * Asks questions through the renderer's input line: the prompt is printed, the answer read with
 * {@link Renderer#readLine}. One question at a time; an answer outside the choices is asked again a few times, then
 * counts as no answer, as do end of input and Ctrl+C.
 */
public final class TerminalInteractionImpl implements UserInteraction {

    /** How often an invalid answer is asked again before giving up. */
    public static final int MAX_ATTEMPTS = 3;

    private static final List<String> YES_NO = List.of("y", "n");

    private final Renderer renderer;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Creates the interaction.
     *
     * @param renderer the renderer whose input line is used
     */
    public TerminalInteractionImpl(Renderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String ask(String prompt, List<String> choices) {
        String inputPrompt = "[" + String.join("/", choices) + "] ";
        lock.lock();
        try {
            renderer.println(prompt);
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                String answer = renderer.readLine(inputPrompt).strip().toLowerCase(Locale.ROOT);
                for (String c : choices) {
                    if (c.toLowerCase(Locale.ROOT).equals(answer)) {
                        return c;
                    }
                }
                renderer.println("please answer " + String.join(", ", choices));
            }
            return "";
        } catch (UserInterruptException | EndOfFileException | IOException e) {
            return "";
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean confirm(String prompt) {
        return "y".equals(ask(prompt, YES_NO));
    }
}
