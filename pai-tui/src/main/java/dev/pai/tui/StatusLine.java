/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.Locale;
import java.util.Objects;

import dev.pai.model.Usage;

/**
 * Content of the bottom status line: {@code model · ctx% · tokens · session · phase}.
 *
 * @param model provider/model
 * @param ctxPercent estimated context use in percent of the window
 * @param usage tokens used by the session so far
 * @param sessionShort short session id
 * @param phase {@code idle}, {@code thinking} or {@code tool}
 */
public record StatusLine(String model, int ctxPercent, Usage usage, String sessionShort, String phase) {

    /** Phase while waiting for input. */
    public static final String IDLE = "idle";
    /** Phase while the model is replying. */
    public static final String THINKING = "thinking";
    /** Phase while tools run. */
    public static final String TOOL = "tool";

    private static final double THOUSAND = 1000.0d;

    /**
     * Validates fields.
     */
    public StatusLine {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(sessionShort, "sessionShort");
        Objects.requireNonNull(phase, "phase");
    }

    /**
     * Returns a copy with another phase.
     *
     * @param p the phase
     * @return the copy
     */
    public StatusLine withPhase(String p) {
        return new StatusLine(model, ctxPercent, usage, sessionShort, p);
    }

    /**
     * Renders the line.
     *
     * @param separator separator between parts
     * @return the text
     */
    public String render(String separator) {
        return String.join(separator, model, "ctx " + ctxPercent + "%",
            "in " + tokens(usage.input() + usage.cacheRead() + usage.cacheWrite()) + " out " + tokens(usage.output()),
            sessionShort, phase);
    }

    private static String tokens(long n) {
        return n < THOUSAND ? Long.toString(n) : String.format(Locale.ROOT, "%.1fk", n / THOUSAND);
    }
}
