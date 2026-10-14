/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.internal.LoopSnapshot;
import dev.pai.core.loop.LoopState;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.model.CancelToken;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

import static dev.pai.core.compaction.CompactionPolicyTest.call;
import static dev.pai.core.compaction.CompactionPolicyTest.model;
import static dev.pai.core.compaction.CompactionPolicyTest.result;
import static dev.pai.core.compaction.CompactionPolicyTest.say;
import static dev.pai.core.compaction.CompactionPolicyTest.user;
import static org.assertj.core.api.Assertions.assertThat;

class CompactingHooksTest {

    private static final Instant T0 = Instant.parse("2026-10-15T08:00:00Z");

    @TempDir
    Path root;
    @TempDir
    Path cwd;

    private JsonlSessionStoreImpl store;
    private SessionId session;
    private final List<Message> context = new ArrayList<>();

    private void persist(Message... messages) {
        for (Message m : messages) {
            Optional<String> leaf = store.leafId(session);
            store.append(session, new SessionEntry.MessageEntry("", leaf, T0, m));
            context.add(m);
        }
    }

    private void setUp() {
        store = new JsonlSessionStoreImpl(root, false, Clock.fixed(T0, ZoneOffset.UTC));
        session = store.create(cwd);
        persist(user("u1 " + "x".repeat(400)), say("a1"), user("u2"), call("c1"), result("c1"), say("a2"), user("u3"),
            call("c2"), result("c2"), say("a3"));
    }

    private static LoopState state(List<Message> m, int window, boolean overflow) {
        return new LoopSnapshot(m, model(window), Usage.ZERO, 0, Path.of("."), overflow, CancelToken.none());
    }

    private CompactingHooks hooks(FakeSummarizerImpl sum) {
        return new CompactingHooks(new CharsTokenEstimatorImpl(), new CompactionPolicy(0, 0, 3), sum, store,
            () -> session, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void below_threshold_nothing_happens() {
        setUp();
        FakeSummarizerImpl sum = new FakeSummarizerImpl(false);
        List<Message> out = hooks(sum).transformContext(List.copyOf(context), state(context, 100_000, false));
        assertThat(out).isEqualTo(context);
        assertThat(sum.calls).isEmpty();
        assertThat(store.readAll(session)).noneMatch(SessionEntry.CompactionEntry.class::isInstance);
    }

    @Test
    void over_threshold_summarizes_writes_entry_and_matches_build_context() {
        setUp();
        FakeSummarizerImpl sum = new FakeSummarizerImpl(false);
        List<Message> out = hooks(sum).transformContext(List.copyOf(context), state(context, 100, false));
        assertThat(sum.calls).hasSize(1);
        assertThat(sum.calls.get(0)).isEqualTo(context.subList(0, 6));
        assertThat(out).hasSize(5);
        assertThat(((ContentBlock.Text)((UserMessage)out.get(0)).content().get(0)).text())
            .isEqualTo(JsonlSessionStoreImpl.SUMMARY_PREFIX + "summary of 6");
        assertThat(out.subList(1, 5)).isEqualTo(context.subList(6, 10));
        SessionEntry last = store.readAll(session).get(store.readAll(session).size() - 1);
        assertThat(last).isInstanceOfSatisfying(SessionEntry.CompactionEntry.class, c -> {
            assertThat(c.summary()).isEqualTo("summary of 6");
            assertThat(c.tokensBefore()).isGreaterThan(100L);
        });
        assertThat(store.buildContext(session, store.leafId(session).orElseThrow())).isEqualTo(out);
    }

    @Test
    void compacts_again_on_top_of_an_earlier_compaction() {
        setUp();
        CompactingHooks hooks = hooks(new FakeSummarizerImpl(false));
        List<Message> first = hooks.transformContext(List.copyOf(context), state(context, 100, false));
        context.clear();
        context.addAll(first);
        persist(user("u4 " + "y".repeat(400)), say("a4"), user("u5"), say("a5"));
        List<Message> second = hooks.compact(List.copyOf(context), state(context, 100, false), false);
        assertThat(second).hasSize(4);
        assertThat(store.buildContext(session, store.leafId(session).orElseThrow())).isEqualTo(second);
    }

    @Test
    void summarizer_failure_keeps_the_context() {
        setUp();
        List<Message> out =
            hooks(new FakeSummarizerImpl(true)).transformContext(List.copyOf(context), state(context, 100, false));
        assertThat(out).isEqualTo(context);
        assertThat(store.readAll(session)).noneMatch(SessionEntry.CompactionEntry.class::isInstance);
    }

    @Test
    void overflow_forces_compaction_below_threshold() {
        setUp();
        List<Message> out =
            hooks(new FakeSummarizerImpl(false)).transformContext(List.copyOf(context), state(context, 100_000, true));
        assertThat(out).hasSize(5);
    }

    @Test
    void context_that_is_not_the_session_leaf_is_left_alone() {
        setUp();
        List<Message> foreign = new ArrayList<>(context);
        foreign.remove(0);
        FakeSummarizerImpl sum = new FakeSummarizerImpl(false);
        assertThat(hooks(sum).compact(foreign, state(foreign, 10, false), true)).isEqualTo(foreign);
        assertThat(sum.calls).isEmpty();
    }
}
