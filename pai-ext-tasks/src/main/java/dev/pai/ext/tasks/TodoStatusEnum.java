/* pai-next — Apache-2.0 */
package dev.pai.ext.tasks;

import java.util.Locale;
import java.util.Optional;

/**
 * State of one item of the task list.
 */
enum TodoStatusEnum {
    /** Not started. */
    PENDING,
    /** Being worked on; at most one item at a time. */
    IN_PROGRESS,
    /** Done. */
    COMPLETED;

    /**
     * Returns the name used in tool arguments and session entries.
     *
     * @return {@code pending}, {@code in_progress} or {@code completed}
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a wire name.
     *
     * @param text the name
     * @return the status, empty when the text names none
     */
    public static Optional<TodoStatusEnum> parse(String text) {
        for (TodoStatusEnum s : values()) {
            if (s.wireName().equals(text)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
