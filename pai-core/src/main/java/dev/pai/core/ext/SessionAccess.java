/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.session.SessionEntry;

/**
 * The host's view of the active session that extensions write their own entries through. The host implements it because
 * it owns the current leaf: an appended entry becomes the leaf, so the next message continues after it.
 */
public interface SessionAccess {

    /**
     * Appends a {@link SessionEntry.CustomEntry} after the current leaf and makes it the leaf.
     *
     * @param customType extension-defined type
     * @param data payload
     * @return the id of the stored entry, empty when there is no active session
     */
    Optional<String> append(String customType, JsonNode data);

    /**
     * Finds the latest custom entry of a type on the branch that ends at the current leaf.
     *
     * @param customType extension-defined type
     * @return the entry, empty when the branch has none or there is no active session
     */
    Optional<SessionEntry.CustomEntry> latest(String customType);

    /**
     * Returns access for hosts without a session: appends are dropped and nothing is found.
     *
     * @return the access
     */
    static SessionAccess none() {
        return new SessionAccess() {
            @Override
            public Optional<String> append(String customType, JsonNode data) {
                return Optional.empty();
            }

            @Override
            public Optional<SessionEntry.CustomEntry> latest(String customType) {
                return Optional.empty();
            }
        };
    }
}
