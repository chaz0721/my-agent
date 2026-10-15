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
import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.LoopState;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.Message;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.Usage;

import static dev.pai.core.compaction.CompactionPolicyTest.call;
import static dev.pai.core.compaction.CompactionPolicyTest.model;
import static dev.pai.core.compaction.CompactionPolicyTest.result;
import static dev.pai.core.compaction.CompactionPolicyTest.say;
import static dev.pai.core.compaction.CompactionPolicyTest.user;
import static dev.pai.core.compaction.MicroCompactorTest.big;
import static org.assertj.core.api.Assertions.assertThat;

class CompactingHooksMicroTest {

    private static final Instant T0 = Instant.parse("2026-10-15T08:00:00Z");

    @TempDir
    Path root;
    @TempDir
    Path cwd;

    private JsonlSessionStoreImpl store;
    private SessionId session;
    private Optional<String> leaf = Optional.empty();
    private final List<Message> context = new ArrayList<>();
    private final List<AgentEvent> events = new ArrayList<>();

    /** Two early tool results of about 1000 tokens each, then small turns. */
    private void setUp() {
        store = new JsonlSessionStoreImpl(root, false, Clock.fixed(T0, ZoneOffset.UTC));
        session = store.create(cwd);
        persist(user("go"), call("c1"), big("c1", "read", 80), call("c2"), big("c2", "read", 80), say("ok"),
            user("next"), call("c3"), result("c3"), call("c4"), result("c4"), call("c5"), result("c5"), say("done"));
    }

    private void persist(Message... messages) {
        for (Message m : messages) {
            leaf = Optional.of(store.append(session, new SessionEntry.MessageEntry("", leaf, T0, m)));
            context.add(m);
        }
    }

    private LoopState state(int window) {
        return new LoopSnapshot(context, model(window), Usage.ZERO, 0, Path.of("."), false, CancelToken.none(), leaf);
    }

    private CompactingHooks hooks(FakeSummarizerImpl sum) {
        return new CompactingHooks(new CharsTokenEstimatorImpl(), new CompactionPolicy(0, 0, 3), sum, store,
            new CompactingHooks.Target(() -> session)).withListener(events::add);
    }

    private static ChatRequest request(List<Message> messages, int window) {
        return new ChatRequest(model(window), Optional.empty(), messages, List.of(), ChatOptions.defaults());
    }

    @Test
    void micro_compaction_avoids_the_summary_and_trims_only_the_request() {
        setUp();
        FakeSummarizerImpl sum = new FakeSummarizerImpl(false);
        CompactingHooks hooks = hooks(sum);
        List<Message> in = List.copyOf(context);
        assertThat(hooks.transformContext(in, state(1_500))).isSameAs(in);
        assertThat(sum.calls).isEmpty();
        ChatRequest sent = hooks.prepareRequest(request(in, 1_500), state(1_500));
        assertThat(((ToolResultMessage)sent.messages().get(2)).content().toString()).contains("trimmed by pai");
        assertThat(((ToolResultMessage)sent.messages().get(4)).content().toString()).contains("trimmed by pai");
        assertThat(sent.messages().subList(5, in.size())).isEqualTo(in.subList(5, in.size()));
        hooks.prepareRequest(request(in, 1_500), state(1_500));
        assertThat(events).containsExactly(new AgentEvent.ContextCompacted(in.size(), in.size(),
            AgentEvent.ContextCompacted.KindEnum.MICRO, Optional.empty()));
        assertThat(store.readAll(session)).noneMatch(SessionEntry.CompactionEntry.class::isInstance);
        assertThat(store.buildContext(session, leaf.orElseThrow())).isEqualTo(in);
    }

    @Test
    void when_the_default_projection_is_still_too_big_only_the_latest_turn_keeps_its_results() {
        store = new JsonlSessionStoreImpl(root, false, Clock.fixed(T0, ZoneOffset.UTC));
        session = store.create(cwd);
        persist(user("go"), call("c1"), big("c1", "read", 80), say("ok"), user("next"), call("c3"),
            big("c3", "read", 80), call("c4"), result("c4"), call("c5"), result("c5"), say("done"));
        FakeSummarizerImpl sum = new FakeSummarizerImpl(false);
        CompactingHooks hooks = hooks(sum);
        List<Message> in = List.copyOf(context);
        assertThat(hooks.transformContext(in, state(600))).isSameAs(in);
        assertThat(sum.calls).isEmpty();
        List<Message> sent = hooks.prepareRequest(request(in, 600), state(600)).messages();
        assertThat(((ToolResultMessage)sent.get(6)).content().toString()).contains("trimmed by pai: read, 3999 chars");
        assertThat(hooks.projected(in, model(1_500)).get(6)).isSameAs(in.get(6));
    }

    @Test
    void below_the_trigger_share_the_request_is_untouched() {
        setUp();
        CompactingHooks hooks = hooks(new FakeSummarizerImpl(false));
        ChatRequest req = request(List.copyOf(context), 100_000);
        assertThat(hooks.prepareRequest(req, state(100_000))).isSameAs(req);
        assertThat(events).isEmpty();
    }

    @Test
    void still_over_the_threshold_after_trimming_summarizes_and_reports_the_summary() {
        setUp();
        CompactingHooks hooks = hooks(new FakeSummarizerImpl(false));
        List<Message> in = List.copyOf(context);
        List<Message> out = hooks.transformContext(in, state(20));
        assertThat(out).isNotSameAs(in);
        assertThat(store.buildContext(session, store.leafId(session).orElseThrow())).isEqualTo(out);
        assertThat(events).hasSize(1).first().isInstanceOfSatisfying(AgentEvent.ContextCompacted.class, e -> {
            assertThat(e.kind()).isEqualTo(AgentEvent.ContextCompacted.KindEnum.SUMMARY);
            assertThat(e.summary()).isPresent();
            assertThat(e.messagesAfter()).isEqualTo(out.size());
        });
    }

    @Test
    void the_old_constructor_means_a_summary_without_text() {
        AgentEvent.ContextCompacted e = new AgentEvent.ContextCompacted(4, 2);
        assertThat(e.kind()).isEqualTo(AgentEvent.ContextCompacted.KindEnum.SUMMARY);
        assertThat(e.summary()).isEmpty();
    }
}
