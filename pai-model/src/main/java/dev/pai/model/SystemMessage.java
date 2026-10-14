/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.util.Objects;

/**
 * System instructions carried inside a message history.
 *
 * @param text instruction text
 */
public record SystemMessage(String text) implements Message {

    /**
     * Validates the text.
     */
    public SystemMessage {
        Objects.requireNonNull(text, "text");
    }
}
