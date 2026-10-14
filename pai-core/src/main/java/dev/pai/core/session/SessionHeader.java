/* pai-next — Apache-2.0 */
package dev.pai.core.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * First line of a session file.
 *
 * @param version format version
 * @param id session id
 * @param cwd working directory the session is bound to
 * @param createdAt creation time
 */
public record SessionHeader(int version, SessionId id, Path cwd, Instant createdAt) {

    /** Current format version. */
    public static final int CURRENT_VERSION = 1;

    /**
     * Validates fields.
     */
    public SessionHeader {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
