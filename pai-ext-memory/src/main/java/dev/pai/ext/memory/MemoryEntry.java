/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One remembered fact. Deleting appends the same entry with {@code deletedAt} set; the latest line per id wins.
 *
 * @param id short id shown to the user
 * @param text the fact
 * @param tags free-form tags
 * @param createdAt when it was remembered
 * @param deletedAt when it was deleted, if it was
 * @param scope {@value MemoryStore#GLOBAL} or {@value MemoryStore#PROJECT}
 */
record MemoryEntry(String id, String text, List<String> tags, Instant createdAt, Optional<Instant> deletedAt,
    String scope) {

    MemoryEntry {
        tags = List.copyOf(tags);
        Objects.requireNonNull(deletedAt, "deletedAt");
    }

    MemoryEntry deleted(Instant at) {
        return new MemoryEntry(id, text, tags, createdAt, Optional.of(at), scope);
    }
}
