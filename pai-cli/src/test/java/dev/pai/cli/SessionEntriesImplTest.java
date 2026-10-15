/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.model.Json;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class SessionEntriesImplTest {

    @TempDir
    Path dir;

    private SessionStore store;
    private SessionId session;
    private final AtomicReference<Optional<String>> leaf = new AtomicReference<>(Optional.empty());
    private final AtomicReference<Optional<SessionEntriesImpl.Cursor>> cursor = new AtomicReference<>(Optional.empty());
    private SessionEntriesImpl entries;

    private void open() {
        store = new JsonlSessionStoreImpl(dir.resolve("sessions"));
        session = store.create(dir);
        cursor
            .set(Optional.of(new SessionEntriesImpl.Cursor(() -> session, leaf::get, id -> leaf.set(Optional.of(id)))));
        entries = new SessionEntriesImpl(store, cursor::get, Clock.systemUTC(),
            SessionEntriesImpl.filesUnder(dir.resolve("sessions")));
    }

    private String message(String text) {
        String id =
            store.append(session, new SessionEntry.MessageEntry("", leaf.get(), Instant.now(), UserMessage.text(text)));
        leaf.set(Optional.of(id));
        return id;
    }

    private static JsonNode data(String value) {
        return Json.mapper().createObjectNode().put("v", value);
    }

    private ExtensionContext context() {
        AtomicReference<ExtensionContext> ref = new AtomicReference<>();
        Extension probe = new Extension() {
            @Override
            public String id() {
                return "probe";
            }

            @Override
            public void onLoad(ExtensionContext ctx) {
                ref.set(ctx);
            }
        };
        new ExtensionLoader().install(List.of(probe), ExtensionHost.Settings.of(dir, dir).withSession(entries));
        return ref.get();
    }

    @Test
    void an_appended_entry_becomes_the_leaf_and_messages_continue_after_it() {
        open();
        message("one");
        ExtensionContext ctx = context();
        ctx.appendEntry("tasks", data("a"));
        String custom = leaf.get().orElseThrow();
        assertThat(store.readAll(session)).last().isInstanceOfSatisfying(SessionEntry.CustomEntry.class,
            e -> assertThat(e.id()).isEqualTo(custom));
        message("two");
        assertThat(store.buildContext(session, leaf.get().orElseThrow())).hasSize(2);
        assertThat(ctx.latestEntry("tasks")).map(e -> e.data().get("v").asText()).contains("a");
        assertThat(ctx.latestEntry("other")).isEmpty();
    }

    @Test
    void a_fork_sees_only_entries_of_its_own_branch() {
        open();
        String root = message("root");
        entries.append("tasks", data("main"));
        message("main branch");
        leaf.set(Optional.of(root));
        assertThat(entries.latest("tasks")).isEmpty();
        entries.append("tasks", data("fork"));
        message("fork branch");
        assertThat(entries.latest("tasks")).map(e -> e.data().get("v").asText()).contains("fork");
    }

    @Test
    void the_latest_entry_survives_compaction() {
        open();
        message("old");
        entries.append("tasks", data("before"));
        String kept = message("kept");
        String compaction = store.append(session,
            new SessionEntry.CompactionEntry("", leaf.get(), Instant.now(), "summary", kept, 100L));
        leaf.set(Optional.of(compaction));
        message("after");
        assertThat(store.buildContext(session, leaf.get().orElseThrow())).hasSize(3);
        assertThat(entries.latest("tasks")).map(e -> e.data().get("v").asText()).contains("before");
    }

    @Test
    void without_a_session_appends_are_dropped() {
        open();
        cursor.set(Optional.empty());
        assertThat(entries.append("tasks", data("x"))).isEmpty();
        assertThat(entries.latest("tasks")).isEmpty();
        context().appendEntry("tasks", data("x"));
        assertThat(store.readAll(session)).isEmpty();
    }

    @Test
    void the_branch_is_read_again_only_when_the_leaf_moves() throws IOException {
        open();
        message("one");
        entries.append("tasks", data("a"));
        assertThat(entries.latest("tasks")).map(e -> e.data().path("v").asText()).contains("a");
        Path file;
        try (Stream<Path> s = Files.walk(dir.resolve("sessions"))) {
            file = s.filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow();
        }
        String saved = Files.readString(file);
        Files.writeString(file, saved.lines().findFirst().orElseThrow() + "\n");
        assertThat(entries.latest("tasks")).as("served from the cache").isPresent();
        Files.writeString(file, saved);
        entries.append("tasks", data("b"));
        assertThat(entries.latest("tasks")).map(e -> e.data().path("v").asText()).contains("b");
    }

    private Path sessionFile() throws IOException {
        try (Stream<Path> s = Files.walk(dir.resolve("sessions"))) {
            return s.filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow();
        }
    }

    @Test
    void after_the_leaf_moves_only_appended_complete_lines_are_parsed() throws IOException {
        open();
        message("one");
        entries.append("tasks", data("a"));
        assertThat(entries.latest("tasks")).map(e -> e.data().path("v").asText()).contains("a");
        Path file = sessionFile();
        String partial = "{\"type\":\"custom\",\"id\":\"zz";
        Files.writeString(file, partial, java.nio.file.StandardOpenOption.APPEND);
        entries.append("tasks", data("b"));
        assertThat(entries.latest("tasks")).as("the cut line is skipped like the store skips it")
            .map(e -> e.data().path("v").asText()).contains("b");
        assertThat(store.readAll(session)).filteredOn(SessionEntry.CustomEntry.class::isInstance).hasSize(2);
        SessionEntriesImpl fresh = new SessionEntriesImpl(store, cursor::get, Clock.systemUTC(),
            SessionEntriesImpl.filesUnder(dir.resolve("sessions")));
        assertThat(fresh.latest("tasks")).as("a new reader parses the whole file").map(e -> e.data().path("v").asText())
            .contains("b");
    }

    @Test
    void without_the_file_the_store_is_read() {
        open();
        message("one");
        SessionEntriesImpl blind =
            new SessionEntriesImpl(store, cursor::get, Clock.systemUTC(), id -> Optional.empty());
        blind.append("tasks", data("a"));
        assertThat(blind.latest("tasks")).map(e -> e.data().path("v").asText()).contains("a");
    }
}
