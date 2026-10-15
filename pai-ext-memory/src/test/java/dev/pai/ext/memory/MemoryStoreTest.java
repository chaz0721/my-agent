/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStoreTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;
    @TempDir
    Path other;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void add_list_delete_and_clear_append_only() throws IOException {
        MemoryStore s = new MemoryStore(home, cwd, clock);
        MemoryEntry a = s.add("  prefers tabs  ", List.of("style"), MemoryStore.GLOBAL);
        MemoryEntry b = s.add("build with mvn -q verify", List.of(), MemoryStore.PROJECT);
        assertThat(a.text()).isEqualTo("prefers tabs");
        assertThat(s.list()).extracting(MemoryEntry::id).containsExactlyInAnyOrder(a.id(), b.id());
        assertThat(s.delete(a.id())).isTrue();
        assertThat(s.delete("nope")).isFalse();
        assertThat(s.list()).extracting(MemoryEntry::id).containsExactly(b.id());
        assertThat(Files.readAllLines(s.file(MemoryStore.GLOBAL))).hasSize(2).last().asString().contains("deletedAt");
        assertThat(s.clear()).isEqualTo(1);
        assertThat(s.list()).isEmpty();
        assertThat(Files.readAllLines(s.file(MemoryStore.PROJECT))).hasSize(2);
    }

    @Test
    void project_memories_stay_with_their_directory_and_survive_a_new_store() {
        new MemoryStore(home, cwd, clock).add("global fact", List.of(), MemoryStore.GLOBAL);
        new MemoryStore(home, cwd, clock).add("project fact", List.of("x"), MemoryStore.PROJECT);
        assertThat(new MemoryStore(home, cwd, clock).list()).extracting(MemoryEntry::text)
            .containsExactlyInAnyOrder("global fact", "project fact");
        assertThat(new MemoryStore(home, other, clock).list()).extracting(MemoryEntry::text)
            .containsExactly("global fact");
        assertThat(new MemoryStore(home, cwd, clock).list()).filteredOn(e -> e.scope().equals(MemoryStore.PROJECT))
            .singleElement().satisfies(e -> assertThat(e.tags()).containsExactly("x"));
    }

    @Test
    void unreadable_lines_are_skipped() throws IOException {
        MemoryStore s = new MemoryStore(home, cwd, clock);
        s.add("kept", List.of(), MemoryStore.GLOBAL);
        Files.writeString(s.file(MemoryStore.GLOBAL), "{broken\n\n", java.nio.file.StandardOpenOption.APPEND);
        assertThat(s.list()).extracting(MemoryEntry::text).containsExactly("kept");
    }
}
