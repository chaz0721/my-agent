/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One remembered fact: a line {@code - [id] text #tag} of a scope's {@code MEMORY.md} or of one of its topic files.
 *
 * @param id short id shown to the user
 * @param text the fact
 * @param tags free-form tags
 * @param scope {@value MemoryStore#GLOBAL} or {@value MemoryStore#PROJECT}
 * @param topic the topic file it lives in, empty for {@code MEMORY.md}
 */
record MemoryEntry(String id, String text, List<String> tags, String scope, Optional<String> topic) {

    MemoryEntry {
        tags = List.copyOf(tags);
        Objects.requireNonNull(topic, "topic");
    }
}
