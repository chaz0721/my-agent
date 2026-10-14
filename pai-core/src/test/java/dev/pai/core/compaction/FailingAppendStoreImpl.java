/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionHeader;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.model.Message;

/**
 * Store for tests whose appends fail as on a full or read-only disk; reads go to a real store.
 */
final class FailingAppendStoreImpl implements SessionStore {

    private final SessionStore delegate;

    FailingAppendStoreImpl(SessionStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public SessionId create(Path cwd) {
        return delegate.create(cwd);
    }

    @Override
    public SessionHeader header(SessionId id) {
        return delegate.header(id);
    }

    @Override
    public String append(SessionId id, SessionEntry entry) {
        throw new UncheckedIOException(new IOException("disk full"));
    }

    @Override
    public List<SessionEntry> readAll(SessionId id) {
        return delegate.readAll(id);
    }

    @Override
    public Optional<String> leafId(SessionId id) {
        return delegate.leafId(id);
    }

    @Override
    public List<Message> buildContext(SessionId id, String leafEntryId) {
        return delegate.buildContext(id, leafEntryId);
    }

    @Override
    public Optional<SessionId> mostRecent(Path cwd) {
        return delegate.mostRecent(cwd);
    }

    @Override
    public List<SessionId> list(Path cwd) {
        return delegate.list(cwd);
    }
}
