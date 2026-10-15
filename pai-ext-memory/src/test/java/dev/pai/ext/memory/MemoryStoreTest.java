/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    private MemoryStore store() {
        return new MemoryStore(home, cwd);
    }

    @Test
    void add_list_and_delete_edit_the_markdown_files() throws IOException {
        MemoryStore s = store();
        MemoryEntry a = s.add("  prefers tabs\n over spaces ", List.of("style"), MemoryStore.GLOBAL, Optional.empty());
        MemoryEntry b = s.add("build with mvn -q verify", List.of(), MemoryStore.PROJECT, Optional.empty());
        assertThat(a.text()).isEqualTo("prefers tabs over spaces");
        assertThat(s.file(MemoryStore.GLOBAL)).isEqualTo(home.resolve("memory/global/MEMORY.md"));
        assertThat(Files.readAllLines(s.file(MemoryStore.GLOBAL)))
            .contains("- [" + a.id() + "] prefers tabs over spaces #style");
        assertThat(Files.readString(s.file(MemoryStore.PROJECT).getParent().resolve(MemoryStore.PATH_NOTE)).strip())
            .isEqualTo(cwd.toAbsolutePath().normalize().toString());
        assertThat(s.list()).extracting(MemoryEntry::id).containsExactly(b.id(), a.id());
        assertThat(s.delete(a.id())).isTrue();
        assertThat(s.delete("nope")).isFalse();
        assertThat(Files.readString(s.file(MemoryStore.GLOBAL))).doesNotContain("tabs");
        assertThat(s.clear()).isEqualTo(1);
        assertThat(s.list()).isEmpty();
        assertThat(Files.readAllLines(s.file(MemoryStore.PROJECT))).hasSize(3).allMatch(l -> l.startsWith("<!--"))
            .anyMatch(l -> l.contains("id derived from its text"));
    }

    @Test
    void hand_edits_are_read_at_once() throws IOException {
        MemoryStore s = store();
        MemoryEntry e = s.add("old wording", List.of(), MemoryStore.PROJECT, Optional.empty());
        Path f = s.file(MemoryStore.PROJECT);
        String edited = Files.readString(f).replace("old wording", "new wording #edited")
            + "\n## notes\nsome prose\n* written by hand\n";
        Files.writeString(f, edited, StandardCharsets.UTF_8);
        assertThat(s.list()).extracting(MemoryEntry::text).containsExactly("new wording", "written by hand");
        assertThat(s.list().get(0).id()).isEqualTo(e.id());
        assertThat(s.list().get(0).tags()).containsExactly("edited");
        String derived = s.list().get(1).id();
        assertThat(store().list().get(1).id()).isEqualTo(derived);
        assertThat(s.delete(derived)).isTrue();
        assertThat(Files.readString(f)).contains("## notes", "some prose").doesNotContain("by hand");
    }

    @Test
    void project_memories_stay_with_their_directory() {
        store().add("global fact", List.of(), MemoryStore.GLOBAL, Optional.empty());
        store().add("project fact", List.of(), MemoryStore.PROJECT, Optional.empty());
        assertThat(store().list()).extracting(MemoryEntry::text).containsExactly("project fact", "global fact");
        assertThat(new MemoryStore(home, other).list()).extracting(MemoryEntry::text).containsExactly("global fact");
    }

    @Test
    void a_topic_goes_to_its_own_file_with_one_index_line() {
        MemoryStore s = store();
        s.add("run mvn -q verify before committing", List.of(), MemoryStore.PROJECT, Optional.of("build"));
        s.add("spotless:apply fixes formatting", List.of(), MemoryStore.PROJECT, Optional.of("build"));
        assertThat(s.list()).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo("topic-build");
            assertThat(e.text()).contains("run mvn -q verify", "recall topic \"build\"");
        });
        assertThat(s.topics(MemoryStore.PROJECT)).containsExactly("build");
        assertThat(s.topic("build").orElseThrow()).contains("run mvn -q verify", "spotless:apply");
        assertThat(s.topic("nothing")).isEmpty();
        assertThat(s.all()).hasSize(3).filteredOn(e -> e.topic().isPresent()).hasSize(2);
        assertThat(MemoryStore.topicName(" Build ")).contains("build");
        assertThat(MemoryStore.topicName("../x")).isEmpty();
        for (String reserved : List.of("memory", "MEMORY", "con", "Nul", "aux", "prn", "com1", "lpt9")) {
            assertThat(MemoryStore.topicName(reserved)).as(reserved).isEmpty();
        }
        assertThat(MemoryStore.topicName("com10")).contains("com10");
    }

    @Test
    void the_old_jsonl_files_are_migrated_once_and_kept() throws IOException {
        Path dir = home.resolve("memory");
        Files.createDirectories(dir);
        Path old = dir.resolve("global.jsonl");
        Files.writeString(old, String.join("\n",
            "{\"id\":\"a1b2c3d4\",\"text\":\"likes tea\",\"tags\":[\"drink\"],\"createdAt\":\"2026-10-01T00:00:00Z\"}",
            "{\"id\":\"deadbeef\",\"text\":\"gone\",\"tags\":[],\"createdAt\":\"2026-10-01T00:00:00Z\"}",
            "{\"id\":\"deadbeef\",\"text\":\"gone\",\"tags\":[],\"createdAt\":\"2026-10-01T00:00:00Z\","
                + "\"deletedAt\":\"2026-10-02T00:00:00Z\"}",
            "{broken"), StandardCharsets.UTF_8);
        String hash = MemoryStore.hash(cwd.toAbsolutePath().normalize().toString());
        Files.writeString(dir.resolve(hash + ".jsonl"),
            "{\"id\":\"0f0f0f0f\",\"text\":\"uses JDK 21\",\"tags\":[],\"createdAt\":\"2026-10-01T00:00:00Z\"}\n");
        assertThat(store().migrate()).isEqualTo(2);
        assertThat(old).doesNotExist();
        assertThat(dir.resolve("global.jsonl.migrated")).exists();
        assertThat(dir.resolve(hash + ".jsonl.migrated")).exists();
        List<String> before = Files.readAllLines(store().file(MemoryStore.GLOBAL));
        assertThat(store().migrate()).isZero();
        assertThat(Files.readAllLines(store().file(MemoryStore.GLOBAL))).isEqualTo(before);
        assertThat(store().list()).extracting(MemoryFile::format).containsExactly("- [0f0f0f0f] uses JDK 21",
            "- [a1b2c3d4] likes tea #drink");
        Files.copy(dir.resolve("global.jsonl.migrated"), old);
        assertThat(store().migrate()).isZero();
        assertThat(dir.resolve("global.jsonl.migrated2")).exists();
        assertThat(store().list()).hasSize(2);
    }

    @Test
    void injection_takes_at_most_60_lines_project_first() {
        MemoryStore s = store();
        s.add("global one", List.of(), MemoryStore.GLOBAL, Optional.empty());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            ids.add(s.add("project " + i, List.of(), MemoryStore.PROJECT, Optional.empty()).id());
        }
        String section = new MemoryInjection(s).section().orElseThrow();
        assertThat(section).contains("[project]\n- [" + ids.get(0) + "] project 0", "project 59")
            .doesNotContain("project 60", "global one");
        assertThat(section.lines().filter(l -> l.startsWith("- ["))).hasSize(MemoryInjection.MAX_LINES);
        MemoryStore few = new MemoryStore(home, other);
        assertThat(new MemoryInjection(few).section().orElseThrow()).contains("[global]\n- [").contains("global one");
    }

    @Test
    void injection_stays_within_4000_characters() {
        MemoryStore s = store();
        for (int i = 0; i < 20; i++) {
            s.add(i + " " + "y".repeat(400), List.of(), MemoryStore.PROJECT, Optional.empty());
        }
        String section = new MemoryInjection(s).section().orElseThrow();
        assertThat(section.length()).isLessThanOrEqualTo(MemoryInjection.MAX_CHARS);
        assertThat(section).endsWith(MemoryInjection.CLOSE).contains("8 yyyy").doesNotContain("9 yyyy");
    }

    @Test
    void files_with_a_bom_or_bytes_that_are_not_utf8_are_still_read() throws IOException {
        MemoryStore s = store();
        Path f = s.file(MemoryStore.GLOBAL);
        Files.createDirectories(f.getParent());
        byte[] bom = {(byte)0xEF, (byte)0xBB, (byte)0xBF};
        byte[] body = "- [a1] first\n- [a2] caf".getBytes(StandardCharsets.UTF_8);
        byte[] latin1 = {(byte)0xE9, '\n'};
        byte[] all = new byte[bom.length + body.length + latin1.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        System.arraycopy(latin1, 0, all, bom.length + body.length, latin1.length);
        Files.write(f, all);
        assertThat(s.list()).extracting(MemoryEntry::id).containsExactly("a1", "a2");
        assertThat(s.list().get(1).text()).startsWith("caf");
    }

    @Test
    void writes_leave_no_temporary_files_and_cuts_keep_surrogate_pairs() throws IOException {
        MemoryStore s = store();
        s.add("one", List.of(), MemoryStore.GLOBAL, Optional.empty());
        s.add("two", List.of(), MemoryStore.GLOBAL, Optional.empty());
        try (java.util.stream.Stream<Path> files = Files.list(s.dir(MemoryStore.GLOBAL))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly(MemoryStore.INDEX);
        }
        String cut = MemoryFile.cut("a" + "\ud83d\ude00".repeat(5), 4);
        assertThat(cut).isEqualTo("a...");
        assertThat(MemoryFile.cut("\ud83d\ude00".repeat(5), 4)).isEqualTo("\ud83d\ude00...");
    }
}
