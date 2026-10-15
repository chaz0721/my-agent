/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.ext.SessionAccess;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.core.session.SessionTree;
import dev.pai.model.Json;

/**
 * The REPL's session as extensions see it: custom entries go after the REPL's leaf, which then moves to them, and
 * lookups walk the branch that ends at that leaf. Before the REPL exists there is no session and nothing happens. The
 * branch of the last lookup is cached: entries are never rewritten, so the path to a given leaf of a given session
 * cannot change. When the leaf moved, only the bytes appended to the session file since the last read are parsed
 * (complete lines only); a file that shrank, or one that cannot be found, is read in full through the store.
 */
final class SessionEntriesImpl implements SessionAccess {

    /**
     * Where the REPL keeps its position.
     *
     * @param session the active session
     * @param leaf the current leaf, empty before the first entry
     * @param moveLeaf makes an appended entry the leaf
     */
    record Cursor(Supplier<SessionId> session, Supplier<Optional<String>> leaf, Consumer<String> moveLeaf) {
    }

    private static final String SUFFIX = ".jsonl";

    private final SessionStore store;
    private final Supplier<Optional<Cursor>> cursor;
    private final Clock clock;
    private final Function<SessionId, Optional<Path>> files;
    private Optional<Branch> cached = Optional.empty();
    private Optional<Read> read = Optional.empty();

    /** The path from the root to a leaf. */
    private record Branch(SessionId session, String leaf, List<SessionEntry> path) {
    }

    /** The entries of a session file parsed so far and the offset after the last complete line. */
    private record Read(SessionId session, Path file, long offset, List<SessionEntry> entries) {
    }

    SessionEntriesImpl(SessionStore store, Supplier<Optional<Cursor>> cursor, Clock clock,
        Function<SessionId, Optional<Path>> files) {
        this.store = store;
        this.cursor = cursor;
        this.clock = clock;
        this.files = files;
    }

    /**
     * Finds session files as {@code JsonlSessionStoreImpl} names them: {@code <sessionsDir>/<hash>/<time>-<id>.jsonl}.
     */
    static Function<SessionId, Optional<Path>> filesUnder(Path sessionsDir) {
        return id -> {
            String name = "-" + id.value() + SUFFIX;
            try (Stream<Path> s = Files.find(sessionsDir, 2, (p, a) -> p.getFileName().toString().endsWith(name))) {
                return s.findFirst();
            } catch (IOException | UncheckedIOException e) {
                return Optional.empty();
            }
        };
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
        List<SessionEntry> path = branch(c.get().session().get(), leaf.get());
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i) instanceof SessionEntry.CustomEntry e && customType.equals(e.customType())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    private List<SessionEntry> branch(SessionId session, String leaf) {
        Optional<Branch> hit = cached.filter(b -> b.session().equals(session) && b.leaf().equals(leaf));
        if (hit.isPresent()) {
            return hit.get().path();
        }
        List<SessionEntry> path = List.copyOf(SessionTree.of(entries(session)).path(leaf));
        cached = Optional.of(new Branch(session, leaf, path));
        return path;
    }

    private List<SessionEntry> entries(SessionId session) {
        Optional<Read> known = read.filter(r -> r.session().equals(session));
        Optional<Path> file = known.map(Read::file).or(() -> files.apply(session));
        if (file.isEmpty()) {
            return store.readAll(session);
        }
        Read r = known.orElse(new Read(session, file.get(), 0L, List.of()));
        try (FileChannel ch = FileChannel.open(file.get(), StandardOpenOption.READ)) {
            long size = ch.size();
            r = size < r.offset() ? new Read(session, file.get(), 0L, List.of()) : r;
            if (size > r.offset()) {
                r = parse(r, ch, size);
            }
        } catch (IOException e) {
            read = Optional.empty();
            return store.readAll(session);
        }
        read = Optional.of(r);
        return r.entries();
    }

    /** Parses the complete lines between the offset and {@code size}; the first line of a file is the header. */
    private static Read parse(Read r, FileChannel ch, long size) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(Math.toIntExact(size - r.offset()));
        while (buf.hasRemaining() && ch.read(buf, r.offset() + buf.position()) >= 0) {
            // read until full
        }
        byte[] bytes = buf.array();
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] != '\n') {
            end--;
        }
        List<SessionEntry> all = new ArrayList<>(r.entries());
        List<String> lines = new String(bytes, 0, end, StandardCharsets.UTF_8).lines().toList();
        for (String line : r.offset() == 0L && !lines.isEmpty() ? lines.subList(1, lines.size()) : lines) {
            try {
                if (!line.isBlank()) {
                    all.add(Json.mapper().readValue(line, SessionEntry.class));
                }
            } catch (JsonProcessingException | IllegalArgumentException e) {
                // a corrupt line is skipped, as the store does
            }
        }
        return new Read(r.session(), r.file(), r.offset() + end, List.copyOf(all));
    }
}
