/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

import dev.pai.core.ext.UserInteraction;

/**
 * Asks questions through the renderer's input line: the prompt is printed, the answer read with
 * {@link Renderer#readLine}. One question at a time; an answer is the choice it equals, else the first one it equals
 * ignoring case ({@code a} and {@code A} can both be choices); an answer outside the choices is asked again a few
 * times, then counts as no answer, as do end of input and Ctrl+C. Control characters in the prompt (other than newline
 * and tab) are shown as {@code \xNN}, so that text from a model or a tool cannot move the cursor or rewrite the
 * question. A renderer that can draw a choice box ({@link Chooser}) asks with that instead; Esc there is no answer.
 */
public final class TerminalInteractionImpl implements UserInteraction {

    /** How often an invalid answer is asked again before giving up. */
    public static final int MAX_ATTEMPTS = 3;

    private static final List<String> YES_NO = List.of("y", "n");
    private static final char DEL = 0x7f;
    private static final char LAST_C1 = 0x9f;

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
            if (renderer instanceof Chooser c) {
                Optional<String> picked = c.choose(visible(prompt), choices);
                if (picked.isPresent()) {
                    return picked.get();
                }
            }
            renderer.println(visible(prompt));
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                String raw = renderer.readLine(inputPrompt).strip();
                String answer = raw.toLowerCase(Locale.ROOT);
                if (choices.contains(raw)) {
                    return raw;
                }
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
        } catch (IllegalStateException e) {
            // the terminal is already reading on another thread (the REPL prompt): nobody can answer now
            return "";
        } finally {
            lock.unlock();
        }
    }

    /**
     * Replaces C0 and C1 control characters and DEL (except newline and tab), Unicode direction controls and zero-width
     * characters by {@code \xNN} or U+NNNN-style {@code \\uNNNN}.
     *
     * @param text any text
     * @return text safe to print
     */
    public static String visible(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean control = c < ' ' && c != '\n' && c != '\t' || c >= DEL && c <= LAST_C1 || invisible(c);
            sb.append(
                control ? String.format(Locale.ROOT, c > LAST_C1 ? "\\u%04x" : "\\x%02x", (int)c) : String.valueOf(c));
        }
        return sb.toString();
    }

    /** Direction overrides and isolates (U+202A..202E, U+2066..2069) and zero-width characters. */
    private static boolean invisible(char c) {
        return c >= '\u202a' && c <= '\u202e' || c >= '\u2066' && c <= '\u2069' || c >= '\u200b' && c <= '\u200d'
            || c == '\u2060' || c == '\ufeff';
    }

    @Override
    public boolean confirm(String prompt) {
        return "y".equals(ask(prompt, YES_NO));
    }
}
