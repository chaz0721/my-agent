/* pai-next — Apache-2.0 */
package dev.pai.core.session;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.UserMessage;

/**
 * {@link SessionStore} over JSONL files: {@code rootDir/<sha256(cwd) first 16 hex>/<yyyyMMdd-HHmmss>-<id>.jsonl}. One
 * JSON object per line, header first; appends never rewrite the file.
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
        List<SessionEntry> existing = readAll(id);
        String entryId = entry.id();
        if (entryId.isEmpty()) {
            entryId = freshId(existing);
        } else {
            String wanted = entryId;
            if (existing.stream().anyMatch(e -> e.id().equals(wanted))) {
                throw new IllegalArgumentException("duplicate entry id: " + entryId);
            }
        }
        String line = toLine(withId(entry, entryId));
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
            ch.write(StandardCharsets.UTF_8.encode(line + "\n"));
            if (fsync) {
                ch.force(false);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append to " + file, e);
        }
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
    public Optional<String> leafId(SessionId id) {
        List<SessionEntry> all = readAll(id);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1).id());
    }

    @Override
    public List<Message> buildContext(SessionId id, String leafEntryId) {
        Map<String, SessionEntry> byId = new HashMap<>();
        for (SessionEntry e : readAll(id)) {
            byId.put(e.id(), e);
        }
        List<SessionEntry> path = new ArrayList<>();
        Optional<String> cursor = Optional.of(leafEntryId);
        while (cursor.isPresent() && byId.containsKey(cursor.get())) {
            SessionEntry e = byId.remove(cursor.get());
            path.add(e);
            cursor = e.parentId();
        }
        Collections.reverse(path);
        int compactionAt = -1;
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i) instanceof SessionEntry.CompactionEntry) {
                compactionAt = i;
            }
        }
        if (compactionAt < 0) {
            return messagesOf(path);
        }
        SessionEntry.CompactionEntry c = (SessionEntry.CompactionEntry)path.get(compactionAt);
        int keepFrom = compactionAt;
        for (int i = 0; i < compactionAt; i++) {
            if (path.get(i).id().equals(c.firstKeptEntryId())) {
                keepFrom = i;
                break;
            }
        }
        List<Message> out = new ArrayList<>();
        out.add(new UserMessage(List.of(new ContentBlock.Text(SUMMARY_PREFIX + c.summary())), c.timestamp()));
        out.addAll(messagesOf(path.subList(keepFrom, compactionAt)));
        out.addAll(messagesOf(path.subList(compactionAt + 1, path.size())));
        return out;
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

    private static List<Message> messagesOf(List<SessionEntry> entries) {
        List<Message> out = new ArrayList<>();
        for (SessionEntry e : entries) {
            if (e instanceof SessionEntry.MessageEntry m) {
                out.add(m.message());
            }
        }
        return out;
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

    private static String freshId(List<SessionEntry> existing) {
        while (true) {
            String candidate = UUID.randomUUID().toString().replace("-", "").substring(0, ENTRY_ID_HEX);
            if (existing.stream().noneMatch(e -> e.id().equals(candidate))) {
                return candidate;
            }
        }
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
