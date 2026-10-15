/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

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
    YOLO;

    /**
     * Parses {@code ask}, {@code auto-read} / {@code auto_read} or {@code yolo}, ignoring case.
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
