/* pai-next — Apache-2.0 */
package dev.pai.core.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonlSessionStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-15T08:00:00Z");

    @TempDir
    Path root;
    @TempDir
    Path cwd;

    private JsonlSessionStoreImpl store() {
        return new JsonlSessionStoreImpl(root, true, Clock.fixed(T0, ZoneOffset.UTC));
    }

    private static SessionEntry.MessageEntry msg(Optional<String> parent, String text) {
        return new SessionEntry.MessageEntry("", parent, T0, UserMessage.text(text));
    }

    private static String text(Message m) {
        return ((ContentBlock.Text)((UserMessage)m).content().get(0)).text();
    }

    private Path onlyFile() throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow();
        }
    }

    @Test
    void create_append_read_all_and_file_layout() throws IOException {
        JsonlSessionStoreImpl store = store();
        SessionId id = store.create(cwd);
        String a = store.append(id, msg(Optional.empty(), "one"));
        String b = store.append(id, msg(Optional.of(a), "two"));
        assertThat(store.readAll(id)).extracting(SessionEntry::id).containsExactly(a, b);
        assertThat(store.leafId(id)).contains(b);
        SessionHeader h = store.header(id);
        assertThat(h.id()).isEqualTo(id);
        assertThat(h.version()).isEqualTo(1);
        assertThat(h.cwd()).isEqualTo(cwd.toAbsolutePath().normalize());

        Path file = onlyFile();
        assertThat(file.getFileName().toString()).isEqualTo("20261015-080000-" + id.value() + ".jsonl");
        assertThat(file.getParent().getFileName().toString()).matches("[0-9a-f]{16}");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(3);
        JsonNode header = Json.mapper().readTree(lines.get(0));
        assertThat(header.path("type").asText()).isEqualTo("session");
        for (String line : lines.subList(1, 3)) {
            JsonNode n = Json.mapper().readTree(line);
            assertThat(n.fieldNames()).toIterable().startsWith("type", "id", "parentId");
        }
        assertThat(Json.mapper().readTree(lines.get(1)).get("parentId").isNull()).isTrue();
        assertThat(Json.mapper().readTree(lines.get(2)).path("parentId").asText()).isEqualTo(a);
    }

    @Test
    void reopened_store_finds_session_by_id_and_rejects_duplicate_ids() {
        SessionId id = store().create(cwd);
        store().append(id, new SessionEntry.MessageEntry("fixed", Optional.empty(), T0, UserMessage.text("x")));
        JsonlSessionStoreImpl fresh = store();
        assertThat(fresh.readAll(id)).hasSize(1);
        assertThatThrownBy(
            () -> fresh.append(id, new SessionEntry.MessageEntry("fixed", Optional.empty(), T0, UserMessage.text("y"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fresh.readAll(new SessionId("missing"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void corrupt_lines_are_skipped() throws IOException {
        JsonlSessionStoreImpl store = store();
        SessionId id = store.create(cwd);
        String a = store.append(id, msg(Optional.empty(), "one"));
        Files.writeString(onlyFile(), "not json\n", StandardOpenOption.APPEND);
        Files.writeString(onlyFile(), "{\"type\":\"message\",\"id\":\"half", StandardOpenOption.APPEND);
        String b = store.append(id, msg(Optional.of(a), "two"));
        String c = store.append(id, msg(Optional.of(b), "three"));
        assertThat(Files.readAllLines(onlyFile()).get(3)).isEqualTo("{\"type\":\"message\",\"id\":\"half");
        assertThat(store.readAll(id)).extracting(SessionEntry::id).containsExactly(a, b, c);
        assertThat(store.buildContext(id, c)).extracting(JsonlSessionStoreTest::text).containsExactly("one", "two",
            "three");
    }

    @Test
    void build_context_follows_the_branch_and_ignores_non_message_entries() {
        JsonlSessionStoreImpl store = store();
        SessionId id = store.create(cwd);
        String a = store.append(id, msg(Optional.empty(), "root"));
        String b = store.append(id, msg(Optional.of(a), "left"));
        String c = store.append(id, msg(Optional.of(a), "right"));
        String m = store.append(id, new SessionEntry.ModelChangeEntry("", Optional.of(c), T0, "p", "m"));
        String l = store.append(id, new SessionEntry.LabelEntry("", Optional.of(m), T0, c, "mark"));
        String x = store.append(id, new SessionEntry.CustomEntry("", Optional.of(l), T0, "ext.note",
            Json.mapper().createObjectNode().put("k", 1)));
        assertThat(store.buildContext(id, b)).extracting(JsonlSessionStoreTest::text).containsExactly("root", "left");
        assertThat(store.buildContext(id, x)).extracting(JsonlSessionStoreTest::text).containsExactly("root", "right");
        assertThat(store.readAll(id).get(5)).isInstanceOf(SessionEntry.CustomEntry.class);
    }

    @Test
    void build_context_applies_latest_compaction() {
        JsonlSessionStoreImpl store = store();
        SessionId id = store.create(cwd);
        String e1 = store.append(id, msg(Optional.empty(), "m1"));
        String e2 = store.append(id, msg(Optional.of(e1), "m2"));
        String e3 = store.append(id, msg(Optional.of(e2), "m3"));
        String c =
            store.append(id, new SessionEntry.CompactionEntry("", Optional.of(e3), T0, "earlier stuff", e3, 1000L));
        String e4 = store.append(id, msg(Optional.of(c), "m4"));
        List<Message> ctx = store.buildContext(id, e4);
        assertThat(ctx).extracting(JsonlSessionStoreTest::text)
            .containsExactly(JsonlSessionStoreImpl.SUMMARY_PREFIX + "earlier stuff", "m3", "m4");
    }

    @Test
    void most_recent_and_list_are_per_cwd_and_ordered(@TempDir Path otherCwd) {
        Instant[] now = {T0};
        Clock ticking = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                now[0] = now[0].plusMillis(1L);
                return now[0];
            }
        };
        JsonlSessionStoreImpl store = new JsonlSessionStoreImpl(root, false, ticking);
        assertThat(store.mostRecent(cwd)).isEmpty();
        SessionId first = store.create(cwd);
        SessionId second = store.create(cwd);
        SessionId other = store.create(otherCwd);
        assertThat(store.list(cwd)).containsExactly(first, second);
        assertThat(store.mostRecent(cwd)).contains(second);
        assertThat(store.mostRecent(otherCwd)).contains(other);
    }
}
