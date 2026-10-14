/* pai-next — Apache-2.0 */
package dev.pai.core.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.Json;
import dev.pai.model.Message;

/**
 * {@link SessionStore} over JSONL files: {@code rootDir/<sha256(cwd) first 16 hex>/<yyyyMMdd-HHmmss>-<id>.jsonl}. One
 * JSON object per line, header first; appends never rewrite the file. Entry ids and the leaf are cached per store
 * instance, so a session must only be appended to through one store instance at a time.
 */
public final class JsonlSessionStoreImpl implements SessionStore {

    /** Prefix of the user message that stands in for compacted history. */
    public static final String SUMMARY_PREFIX =
        "The conversation history before this point was compacted into the following summary:\n\n";

    private static final Logger log = LoggerFactory.getLogger(JsonlSessionStoreImpl.class);
    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final String SUFFIX = ".jsonl";
    private static final int CWD_HASH_HEX = 16;
    private static final int ENTRY_ID_HEX = 8;

    private final Path rootDir;
    private final boolean fsync;
    private final Clock clock;
    private final Map<SessionId, Path> files = Collections.synchronizedMap(new HashMap<>());
    private final Map<SessionId, Index> indexes = new HashMap<>();

    /**
     * Creates a store without fsync.
     *
     * @param rootDir root directory, usually {@code ~/.pai/sessions}
     */
    public JsonlSessionStoreImpl(Path rootDir) {
        this(rootDir, false, Clock.systemUTC());
    }

    /**
     * Creates a store.
     *
     * @param rootDir root directory
     * @param fsync whether to force every append to disk
     * @param clock time source for headers and file names
     */
    public JsonlSessionStoreImpl(Path rootDir, boolean fsync, Clock clock) {
        this.rootDir = rootDir;
        this.fsync = fsync;
        this.clock = clock;
    }

    @Override
    public SessionId create(Path cwd) {
        Path normalized = cwd.toAbsolutePath().normalize();
        Instant now = clock.instant();
        SessionId id = new SessionId(UUID.randomUUID().toString());
        Path file = dirFor(normalized).resolve(FILE_TIME.format(now) + "-" + id.value() + SUFFIX);
        ObjectNode header =
            Json.mapper().createObjectNode().put("type", "session").put("version", SessionHeader.CURRENT_VERSION)
                .put("id", id.value()).put("cwd", normalized.toString()).put("timestamp", now.toString());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, header + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create session file " + file, e);
        }
        files.put(id, file);
        return id;
    }

    @Override
    public SessionHeader header(SessionId id) {
        JsonNode h = parseHeader(fileOf(id)).orElseThrow(() -> new IllegalStateException("corrupt header: " + id));
        return new SessionHeader(h.path("version").asInt(), new SessionId(h.path("id").asText()),
            Path.of(h.path("cwd").asText()), Instant.parse(h.path("timestamp").asText()));
    }

    @Override
    public synchronized String append(SessionId id, SessionEntry entry) {
        Path file = fileOf(id);
        Index index = indexOf(id);
        String entryId = entry.id();
        if (entryId.isEmpty()) {
            entryId = freshId(index.ids());
        } else if (index.ids().contains(entryId)) {
            throw new IllegalArgumentException("duplicate entry id: " + entryId);
        }
        String line = toLine(withId(entry, entryId));
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            String prefix = endsWithNewline(ch) ? "" : "\n";
            ch.position(ch.size());
            ch.write(StandardCharsets.UTF_8.encode(prefix + line + "\n"));
            if (fsync) {
                ch.force(false);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append to " + file, e);
        }
        index.ids().add(entryId);
        indexes.put(id, new Index(index.ids(), Optional.of(entryId)));
        return entryId;
    }

    @Override
    public List<SessionEntry> readAll(SessionId id) {
        Path file = fileOf(id);
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        List<SessionEntry> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                out.add(Json.mapper().readValue(line, SessionEntry.class));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                log.warn("skipping corrupt line {} of {}: {}", i + 1, file, e.getMessage());
            }
        }
        return out;
    }

    @Override
    public synchronized Optional<String> leafId(SessionId id) {
        return indexOf(id).leaf();
    }

    /** Entry ids and last entry of a session, read once per store instance and then maintained by append. */
    private Index indexOf(SessionId id) {
        Index known = indexes.get(id);
        if (known != null) {
            return known;
        }
        List<SessionEntry> all = readAll(id);
        Set<String> ids = new HashSet<>(all.size() * 2);
        all.forEach(e -> ids.add(e.id()));
        Index index = new Index(ids, all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1).id()));
        indexes.put(id, index);
        return index;
    }

    @Override
    public List<Message> buildContext(SessionId id, String leafEntryId) {
        return SessionTree.of(readAll(id)).project(leafEntryId).messages();
    }

    @Override
    public Optional<SessionId> mostRecent(Path cwd) {
        List<SessionId> all = list(cwd);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<SessionId> list(Path cwd) {
        Path dir = dirFor(cwd.toAbsolutePath().normalize());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<SessionHeader> headers = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).toList()) {
                parseHeader(f).ifPresent(h -> {
                    SessionId sid = new SessionId(h.path("id").asText());
                    files.put(sid, f);
                    headers.add(header(sid));
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + dir, e);
        }
        headers.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        return headers.stream().map(SessionHeader::id).toList();
    }

    /** A crash can leave a half line without its newline; the next append must start on a fresh line. */
    private static boolean endsWithNewline(FileChannel ch) throws IOException {
        long size = ch.size();
        if (size == 0L) {
            return true;
        }
        ByteBuffer last = ByteBuffer.allocate(1);
        ch.read(last, size - 1L);
        return last.get(0) == '\n';
    }

    private Path dirFor(Path normalizedCwd) {
        try {
            byte[] digest =
                MessageDigest.getInstance("SHA-256").digest(normalizedCwd.toString().getBytes(StandardCharsets.UTF_8));
            return rootDir.resolve(HexFormat.of().formatHex(digest).substring(0, CWD_HASH_HEX));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Path fileOf(SessionId id) {
        Path known = files.get(id);
        if (known != null) {
            return known;
        }
        String suffix = "-" + id.value() + SUFFIX;
        try (Stream<Path> s = Files.isDirectory(rootDir) ? Files.walk(rootDir, 2) : Stream.empty()) {
            Path found = s.filter(p -> p.getFileName().toString().endsWith(suffix)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown session: " + id.value()));
            files.put(id, found);
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot search " + rootDir, e);
        }
    }

    private static Optional<JsonNode> parseHeader(Path file) {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            Optional<String> first = lines.findFirst();
            if (first.isEmpty()) {
                return Optional.empty();
            }
            JsonNode h = Json.mapper().readTree(first.get());
            return "session".equals(h.path("type").asText()) ? Optional.of(h) : Optional.empty();
        } catch (IOException e) {
            log.warn("unreadable session header in {}", file, e);
            return Optional.empty();
        }
    }

    private static String freshId(Set<String> existing) {
        while (true) {
            String candidate = UUID.randomUUID().toString().replace("-", "").substring(0, ENTRY_ID_HEX);
            if (!existing.contains(candidate)) {
                return candidate;
            }
        }
    }

    private record Index(Set<String> ids, Optional<String> leaf) {
    }

    /** Serializes with a fixed key order: type, id, parentId (explicit null for roots), then the rest. */
    private static String toLine(SessionEntry entry) {
        ObjectNode raw = Json.mapper().valueToTree(entry);
        ObjectNode line = Json.mapper().createObjectNode();
        line.set("type", raw.remove("type"));
        line.set("id", raw.remove("id"));
        JsonNode parent = raw.remove("parentId");
        if (parent == null) {
            line.putNull("parentId");
        } else {
            line.set("parentId", parent);
        }
        line.setAll(raw);
        return line.toString();
    }

    private static SessionEntry withId(SessionEntry e, String id) {
        return switch (e) {
            case SessionEntry.MessageEntry m ->
                new SessionEntry.MessageEntry(id, m.parentId(), m.timestamp(), m.message());
            case SessionEntry.ModelChangeEntry m ->
                new SessionEntry.ModelChangeEntry(id, m.parentId(), m.timestamp(), m.providerId(), m.modelId());
            case SessionEntry.CompactionEntry c -> new SessionEntry.CompactionEntry(id, c.parentId(), c.timestamp(),
                c.summary(), c.firstKeptEntryId(), c.tokensBefore());
            case SessionEntry.LabelEntry l ->
                new SessionEntry.LabelEntry(id, l.parentId(), l.timestamp(), l.targetId(), l.label());
            case SessionEntry.CustomEntry c ->
                new SessionEntry.CustomEntry(id, c.parentId(), c.timestamp(), c.customType(), c.data());
        };
    }
}
