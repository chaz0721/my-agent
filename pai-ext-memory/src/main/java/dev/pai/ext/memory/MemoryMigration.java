/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.Json;

/**
 * One-time conversion of the append-only JSONL memory files of earlier versions ({@code {id, text, tags[], createdAt,
 * deletedAt?}} per line, the latest line per id wins) into {@code MEMORY.md} lines. The old file is renamed to
 * {@code .migrated}, not deleted; ids already in the target are skipped, so an interrupted run can simply run again.
 */
final class MemoryMigration {

    static final String SUFFIX = ".migrated";
    private static final Logger log = LoggerFactory.getLogger(MemoryMigration.class);

    private MemoryMigration() {}

    /** Migrates {@code old} into {@code target}; returns the number of memories added. */
    static int run(Path old, Path target, String scope, String header) {
        if (!Files.isRegularFile(old)) {
            return 0;
        }
        Set<String> present = new HashSet<>();
        MemoryFile.read(target)
            .forEach(l -> MemoryFile.parse(l, scope, Optional.empty()).map(MemoryEntry::id).ifPresent(present::add));
        List<String> lines = new ArrayList<>(MemoryFile.read(target));
        if (lines.isEmpty()) {
            lines.add(header);
        }
        int added = 0;
        for (MemoryEntry e : live(old, scope)) {
            if (present.add(e.id())) {
                lines.add(MemoryFile.format(e));
                added++;
            }
        }
        MemoryFile.write(target, lines);
        Path archive = old.resolveSibling(old.getFileName() + SUFFIX);
        for (int i = 2; Files.exists(archive); i++) {
            archive = old.resolveSibling(old.getFileName() + SUFFIX + i);
        }
        try {
            Files.move(old, archive);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot rename " + old, e);
        }
        log.info("migrated {} memories from {} to {}", added, old, target);
        return added;
    }

    private static List<MemoryEntry> live(Path old, String scope) {
        Map<String, Optional<MemoryEntry>> latest = new LinkedHashMap<>();
        for (String line : MemoryFile.read(old)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode n = Json.mapper().readTree(line);
                List<String> tags = new ArrayList<>();
                n.path("tags").forEach(t -> tags.add(t.asText().replaceAll("\\s+", "-")));
                String id = n.path("id").asText();
                String text = n.path("text").asText().strip().replaceAll("\\s*\\R\\s*", " ");
                latest.remove(id);
                latest.put(id, n.path("deletedAt").isTextual() || text.isEmpty() ? Optional.empty()
                    : Optional.of(new MemoryEntry(id, text, tags, scope, Optional.empty())));
            } catch (JsonProcessingException e) {
                log.warn("skipping unreadable memory line in {}", old, e);
            }
        }
        return latest.values().stream().flatMap(Optional::stream).toList();
    }
}
