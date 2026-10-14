/* pai-next — Apache-2.0 */
package dev.pai.core.session;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTreeTest {

    private static final Instant T0 = Instant.parse("2026-10-15T08:00:00Z");

    @TempDir
    Path root;
    @TempDir
    Path cwd;

    private static String text(Message m) {
        return ((ContentBlock.Text)((UserMessage)m).content().get(0)).text();
    }

    @Test
    void fork_continues_from_an_earlier_entry_and_branches_stay_independent() {
        JsonlSessionStoreImpl store = new JsonlSessionStoreImpl(root, false, Clock.fixed(T0, ZoneOffset.UTC));
        SessionId id = store.create(cwd);
        String a = store.append(id, new SessionEntry.MessageEntry("", Optional.empty(), T0, UserMessage.text("a")));
        String b = store.append(id, new SessionEntry.MessageEntry("", Optional.of(a), T0, UserMessage.text("b")));
        String c = store.append(id, new SessionEntry.MessageEntry("", Optional.of(b), T0, UserMessage.text("c")));
        String fork = store.append(id, new SessionEntry.MessageEntry("", Optional.of(a), T0, UserMessage.text("x")));
        String y = store.append(id, new SessionEntry.MessageEntry("", Optional.of(fork), T0, UserMessage.text("y")));
        store.append(id, new SessionEntry.LabelEntry("", Optional.of(y), T0, b, "before fork"));

        assertThat(store.buildContext(id, c)).extracting(SessionTreeTest::text).containsExactly("a", "b", "c");
        assertThat(store.buildContext(id, y)).extracting(SessionTreeTest::text).containsExactly("a", "x", "y");

        SessionTree tree = SessionTree.of(store.readAll(id));
        assertThat(tree.roots()).extracting(SessionEntry::id).containsExactly(a);
        assertThat(tree.children(a)).extracting(SessionEntry::id).containsExactly(b, fork);
        assertThat(tree.labels(b)).containsExactly("before fork");
        assertThat(tree.path(y)).extracting(SessionEntry::id).containsExactly(a, fork, y);
        assertThat(tree.resolve(c.substring(0, 6))).contains(c);
        assertThat(tree.resolve("")).isEmpty();
        assertThat(tree.resolve("zzzzzzzz")).isEmpty();
        assertThat(tree.project(y).entryIds()).containsExactly(Optional.of(a), Optional.of(fork), Optional.of(y));
    }

    @Test
    void leaf_cache_follows_appends_and_survives_a_new_store_instance() {
        JsonlSessionStoreImpl store = new JsonlSessionStoreImpl(root, false, Clock.fixed(T0, ZoneOffset.UTC));
        SessionId id = store.create(cwd);
        assertThat(store.leafId(id)).isEmpty();
        String a = store.append(id, new SessionEntry.MessageEntry("", Optional.empty(), T0, UserMessage.text("a")));
        assertThat(store.leafId(id)).contains(a);
        String b = store.append(id, new SessionEntry.MessageEntry("", Optional.of(a), T0, UserMessage.text("b")));
        assertThat(store.leafId(id)).contains(b);
        assertThat(new JsonlSessionStoreImpl(root).leafId(id)).contains(b);
    }
}
