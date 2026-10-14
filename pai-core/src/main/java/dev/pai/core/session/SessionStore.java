/* pai-next — Apache-2.0 */
package dev.pai.core.session;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.pai.model.Message;

/**
 * Append-only storage of session entry trees.
 */
public interface SessionStore {

    /**
     * Creates an empty session bound to a working directory.
     *
     * @param cwd the working directory
     * @return the new id
     */
    SessionId create(Path cwd);

    /**
     * Reads the header of a session.
     *
     * @param id the session
     * @return the header
     * @throws IllegalArgumentException when the session does not exist
     */
    SessionHeader header(SessionId id);

    /**
     * Appends one entry. An empty entry id is replaced by a generated one; a given id must be unused.
     *
     * @param id the session
     * @param entry the entry
     * @return the id of the stored entry
     */
    String append(SessionId id, SessionEntry entry);

    /**
     * Reads every readable entry in file order; corrupt lines are skipped with a warning.
     *
     * @param id the session
     * @return the entries
     */
    List<SessionEntry> readAll(SessionId id);

    /**
     * Returns the id of the last appended entry.
     *
     * @param id the session
     * @return the leaf id, empty for a new session
     */
    Optional<String> leafId(SessionId id);

    /**
     * Builds the model context ending at an entry: walks the parent chain to the root and applies the latest compaction
     * on that path.
     *
     * @param id the session
     * @param leafEntryId the last entry of the branch
     * @return messages in order
     */
    List<Message> buildContext(SessionId id, String leafEntryId);

    /**
     * Returns the most recently created session of a working directory.
     *
     * @param cwd the working directory
     * @return the session, if any
     */
    Optional<SessionId> mostRecent(Path cwd);

    /**
     * Lists the sessions of a working directory, oldest first.
     *
     * @param cwd the working directory
     * @return session ids
     */
    List<SessionId> list(Path cwd);
}
