/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.ext.SessionAccess;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.core.session.SessionTree;

/**
 * The REPL's session as extensions see it: custom entries go after the REPL's leaf, which then moves to them, and
 * lookups walk the branch that ends at that leaf. Before the REPL exists there is no session and nothing happens.
 */
final class SessionEntries implements SessionAccess {

    /**
     * Where the REPL keeps its position.
     *
     * @param session the active session
     * @param leaf the current leaf, empty before the first entry
     * @param moveLeaf makes an appended entry the leaf
     */
    record Cursor(Supplier<SessionId> session, Supplier<Optional<String>> leaf, Consumer<String> moveLeaf) {
    }

    private final SessionStore store;
    private final Supplier<Optional<Cursor>> cursor;
    private final Clock clock;

    SessionEntries(SessionStore store, Supplier<Optional<Cursor>> cursor, Clock clock) {
        this.store = store;
        this.cursor = cursor;
        this.clock = clock;
    }

    @Override
    public synchronized Optional<String> append(String customType, JsonNode data) {
        return cursor.get().map(c -> {
            String id = store.append(c.session().get(),
                new SessionEntry.CustomEntry("", c.leaf().get(), clock.instant(), customType, data));
            c.moveLeaf().accept(id);
            return id;
        });
    }

    @Override
    public synchronized Optional<SessionEntry.CustomEntry> latest(String customType) {
        Optional<Cursor> c = cursor.get();
        Optional<String> leaf = c.flatMap(x -> x.leaf().get());
        if (leaf.isEmpty()) {
            return Optional.empty();
        }
        List<SessionEntry> path = SessionTree.of(store.readAll(c.get().session().get())).path(leaf.get());
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i) instanceof SessionEntry.CustomEntry e && customType.equals(e.customType())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }
}
