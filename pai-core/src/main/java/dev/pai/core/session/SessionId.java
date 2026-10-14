/* pai-next — Apache-2.0 */
package dev.pai.core.session;

import java.util.Objects;

/**
 * Identifier of a session file.
 *
 * @param value the id
 */
public record SessionId(String value) {

    /**
     * Validates the value.
     */
    public SessionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || !value.matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("invalid session id: " + value);
        }
    }
}
