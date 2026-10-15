/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * How much the user is asked before a tool call runs. Guards block regardless of the mode.
 */
public enum ApprovalModeEnum {
    /** Every tool call needs approval. */
    ASK,
    /** Read-only tools run without asking while their path stays inside; everything else needs approval (default). */
    AUTO_READ,
    /** Nothing is asked; only guards block. Must be switched on explicitly at startup. */
    YOLO,
    /** Read-only planning: tools that change anything are blocked, read-only ones run as in {@link #AUTO_READ}. */
    PLAN;

    /** Modes Shift+Tab steps through, in order; YOLO is never reached this way. */
    private static final List<ApprovalModeEnum> CYCLE = List.of(ASK, AUTO_READ, PLAN);

    /**
     * Returns the mode after this one in the Shift+Tab cycle ASK, AUTO_READ, PLAN; YOLO goes to ASK.
     *
     * @return the next mode, never YOLO
     */
    public ApprovalModeEnum next() {
        int i = CYCLE.indexOf(this);
        return CYCLE.get((i + 1) % CYCLE.size());
    }

    /**
     * Parses {@code ask}, {@code auto-read} / {@code auto_read}, {@code plan} or {@code yolo}, ignoring case.
     *
     * @param text the text
     * @return the mode, empty when the text names none
     */
    public static Optional<ApprovalModeEnum> parse(String text) {
        String norm = text.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ApprovalModeEnum m : values()) {
            if (m.name().equals(norm)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}
