/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import dev.pai.core.session.SessionId;

/**
 * Session lifecycle events visible to extensions.
 */
public sealed interface SessionEvent permits SessionEvent.SessionStarted, SessionEvent.SessionEnded {

    /**
     * A session became active.
     *
     * @param id the session
     * @param resumed whether an existing session was resumed
     */
    record SessionStarted(SessionId id, boolean resumed) implements SessionEvent {
    }

    /**
     * The active session ended.
     *
     * @param id the session
     */
    record SessionEnded(SessionId id) implements SessionEvent {
    }
}
