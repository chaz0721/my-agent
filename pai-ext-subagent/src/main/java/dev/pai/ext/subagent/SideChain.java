/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionHeader;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.model.Json;
import dev.pai.model.Message;

/**
 * The side-chain transcript of one sub-agent run: an ordinary session file at
 * {@code sessions/<cwd-hash>/subagents/<parentSessionId>/<runId>.jsonl} (next to the parent's file), whose header also
 * carries {@code parentSession}, {@code runId} and {@code agentType}. The run id is {@code sa-<session id>}, so a
 * {@link JsonlSessionStoreImpl} rooted at the run's directory finds the file by its session id and appends to it as to
 * any session; the parent session itself only gets one summary entry.
 */
final class SideChain {

    static final String SUBAGENTS_DIR = "subagents";
    static final String RUN_PREFIX = "sa-";
    private static final String SUFFIX = ".jsonl";
    /** Directory of runs whose parent session is not known (no session started yet). */
    private static final String DETACHED = "detached";

    private final Path file;
    private final SessionStore store;
    private final SessionId id;

    private SideChain(Path file, SessionStore store, SessionId id) {
        this.file = file;
        this.store = store;
        this.id = id;
    }

    /** Creates the file of a run and writes its header. */
    static SideChain create(Path sessionsDir, Optional<String> parentSession, String runId, String agentType,
        Path cwd) {
        Path dir = parentDir(sessionsDir, parentSession).resolve(SUBAGENTS_DIR).resolve(parentSession.orElse(DETACHED));
        Path file = dir.resolve(runId + SUFFIX);
        String sid = runId.substring(RUN_PREFIX.length());
        ObjectNode header =
            Json.mapper().createObjectNode().put("type", "session").put("version", SessionHeader.CURRENT_VERSION)
                .put("id", sid).put("cwd", cwd.toAbsolutePath().normalize().toString())
                .put("timestamp", Instant.now().toString()).put("runId", runId).put("agentType", agentType);
        parentSession.ifPresent(p -> header.put("parentSession", p));
        try {
            Files.createDirectories(dir);
            Files.writeString(file, header + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create side-chain file " + file, e);
        }
        return new SideChain(file, new JsonlSessionStoreImpl(dir), new SessionId(sid));
    }

    /** The directory of the parent session's file ({@code sessions/<cwd-hash>}), or the sessions directory. */
    private static Path parentDir(Path sessionsDir, Optional<String> parentSession) {
        if (parentSession.isEmpty() || !Files.isDirectory(sessionsDir)) {
            return sessionsDir;
        }
        String name = "-" + parentSession.get() + SUFFIX;
        try (Stream<Path> s = Files.find(sessionsDir, 2, (p, a) -> p.getFileName().toString().endsWith(name))) {
            return s.findFirst().map(Path::getParent).orElse(sessionsDir);
        } catch (IOException | UncheckedIOException e) {
            return sessionsDir;
        }
    }

    /** Appends a message after the current leaf (the last entry, compaction entries included). */
    void append(Message m) {
        store.append(id, new SessionEntry.MessageEntry("", store.leafId(id), Instant.now(), m));
    }

    /** The last entry, which the next message and compaction attach to. */
    Optional<String> leaf() {
        return store.leafId(id);
    }

    SessionStore store() {
        return store;
    }

    SessionId id() {
        return id;
    }

    Path file() {
        return file;
    }
}
