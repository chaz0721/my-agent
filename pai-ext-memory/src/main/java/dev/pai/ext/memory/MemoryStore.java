/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.Json;

/**
 * Append-only memory files under {@code <paiHome>/memory/}: {@code global.jsonl} for facts about the user and
 * {@code <cwd-hash>.jsonl} for the working directory. Each line is {@code {id, text, tags[], createdAt, deletedAt?}};
 * nothing is ever rewritten, so the files are an audit trail. Methods are synchronized.
 */
final class MemoryStore {

    static final String GLOBAL = "global";
    static final String PROJECT = "project";
    static final String DIR = "memory";
    private static final int ID_LENGTH = 8;
    private static final int HASH_HEX = 16;
    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    private final Path globalFile;
    private final Path projectFile;
    private final Clock clock;

    MemoryStore(Path paiHome, Path cwd, Clock clock) {
        Path dir = paiHome.resolve(DIR);
        this.globalFile = dir.resolve(GLOBAL + ".jsonl");
        this.projectFile = dir.resolve(hash(cwd.toAbsolutePath().normalize().toString()) + ".jsonl");
        this.clock = clock;
    }

    /** Live entries of both scopes, oldest first. */
    synchronized List<MemoryEntry> list() {
        List<MemoryEntry> out = new ArrayList<>(live(GLOBAL));
        out.addAll(live(PROJECT));
        out.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        return out;
    }

    synchronized MemoryEntry add(String text, List<String> tags, String scope) {
        List<MemoryEntry> all = list();
        String id;
        do {
            id = UUID.randomUUID().toString().substring(0, ID_LENGTH);
        } while (find(all, id).isPresent());
        MemoryEntry e = new MemoryEntry(id, text.strip(), tags, clock.instant(), Optional.empty(), scope);
        append(e);
        return e;
    }

    synchronized boolean delete(String id) {
        Optional<MemoryEntry> e = find(list(), id);
        e.ifPresent(x -> append(x.deleted(clock.instant())));
        return e.isPresent();
    }

    /** Deletes every live entry of both scopes; returns how many. */
    synchronized int clear() {
        List<MemoryEntry> all = list();
        Instant now = clock.instant();
        all.forEach(e -> append(e.deleted(now)));
        return all.size();
    }

    Path file(String scope) {
        return GLOBAL.equals(scope) ? globalFile : projectFile;
    }

    private static Optional<MemoryEntry> find(List<MemoryEntry> entries, String id) {
        return entries.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    private List<MemoryEntry> live(String scope) {
        Path f = file(scope);
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        Map<String, MemoryEntry> latest = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                parse(line, scope).ifPresent(e -> latest.put(e.id(), e));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + f, e);
        }
        return latest.values().stream().filter(e -> e.deletedAt().isEmpty()).toList();
    }

    private static Optional<MemoryEntry> parse(String line, String scope) {
        if (line.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode n = Json.mapper().readTree(line);
            List<String> tags = new ArrayList<>();
            n.path("tags").forEach(t -> tags.add(t.asText()));
            Optional<Instant> deleted = n.path("deletedAt").isTextual()
                ? Optional.of(Instant.parse(n.get("deletedAt").asText())) : Optional.empty();
            return Optional.of(new MemoryEntry(n.path("id").asText(), n.path("text").asText(), tags,
                Instant.parse(n.path("createdAt").asText()), deleted, scope));
        } catch (JsonProcessingException | DateTimeParseException e) {
            log.warn("skipping unreadable memory line in {} scope", scope, e);
            return Optional.empty();
        }
    }

    private void append(MemoryEntry e) {
        ObjectNode n = Json.mapper().createObjectNode().put("id", e.id()).put("text", e.text());
        ArrayNode tags = n.putArray("tags");
        e.tags().forEach(tags::add);
        n.put("createdAt", e.createdAt().toString());
        e.deletedAt().ifPresent(d -> n.put("deletedAt", d.toString()));
        Path f = file(e.scope());
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, n.toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot write " + f, ex);
        }
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, HASH_HEX);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }
}
