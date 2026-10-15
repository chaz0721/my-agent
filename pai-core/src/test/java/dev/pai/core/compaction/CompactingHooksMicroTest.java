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
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.StopReasonEnum;
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
        assertThat(hooks(sum).projected(in, model(1_500)).get(6)).as("default policy suffices").isSameAs(in.get(6));
        assertThat(hooks.projected(in, model(1_500)).get(6)).as("the tight policy sticks until the next summary")
            .isNotSameAs(in.get(6));
    }

    @Test
    void after_trimming_the_reported_size_is_trusted_and_still_triggers_a_summary() {
        store = new JsonlSessionStoreImpl(root, false, Clock.fixed(T0, ZoneOffset.UTC));
        session = store.create(cwd);
        persist(user("go"), call("c1"), big("c1", "read", 80), call("c2"), big("c2", "read", 80), say("ok"),
            user("next"), call("c3"), result("c3"), call("c4"), result("c4"), call("c5"), result("c5"));
        FakeSummarizerImpl sum = new FakeSummarizerImpl(false);
        CompactingHooks hooks = hooks(sum);
        List<Message> first = List.copyOf(context);
        List<Message> sent = hooks.prepareRequest(request(first, 1_500), state(1_500)).messages();
        assertThat(sent.get(2)).isNotSameAs(first.get(2));
        long trimmedSize = new CharsTokenEstimatorImpl().estimate(sent, List.of(), "");
        AssistantMessage reply = new AssistantMessage(List.of(new ContentBlock.Text("done")), "fake", "m",
            new Usage(1_600L, 10L, 0L, 0L), StopReasonEnum.STOP, Optional.empty(), T0);
        assertThat(reply.usage().input()).as("the provider counted more than the characters suggest")
            .isGreaterThan(trimmedSize);
        persist(reply, user("more"));
        List<Message> second = List.copyOf(context);
        List<Message> out = hooks.transformContext(second, state(1_500));
        assertThat(sum.calls).as("projection estimate >= estimate of the view, which is over the threshold").hasSize(1);
        assertThat(out).isNotSameAs(second);
    }

    @Test
    void a_failed_summary_pauses_automatic_compaction_for_three_turns() {
        setUp();
        FakeSummarizerImpl sum = new FakeSummarizerImpl(true);
        CompactingHooks hooks = hooks(sum);
        List<Message> in = List.copyOf(context);
        for (int i = 0; i < 1 + CompactingHooks.RETRY_AFTER_TURNS; i++) {
            hooks.transformContext(in, state(20));
        }
        assertThat(sum.calls).hasSize(1);
        hooks.compact(in, state(20), true);
        assertThat(sum.calls).as("a forced compaction is not paused").hasSize(2);
        hooks.transformContext(in, state(20));
        assertThat(sum.calls).hasSize(3);
    }

    @Test
    void the_reported_size_of_the_last_reply_does_not_hide_what_trimming_saves() {
        store = new JsonlSessionStoreImpl(root, false, Clock.fixed(T0, ZoneOffset.UTC));
        session = store.create(cwd);
        AssistantMessage measured = new AssistantMessage(List.of(new ContentBlock.Text("read both")), "fake", "m",
            new Usage(2_100L, 10L, 0L, 0L), StopReasonEnum.STOP, Optional.empty(), T0);
        persist(user("go"), call("c1"), big("c1", "read", 80), call("c2"), big("c2", "read", 80), measured,
            user("next"), call("c3"), result("c3"));
        FakeSummarizerImpl sum = new FakeSummarizerImpl(false);
        CompactingHooks hooks = hooks(sum);
        hooks.prepareRequest(request(List.of(user("go")), 1_500), state(1_500));
        List<Message> in = List.copyOf(context);
        assertThat(hooks.transformContext(in, state(1_500))).isSameAs(in);
        assertThat(sum.calls).isEmpty();
    }

    @Test
    void fresh_hooks_trust_a_reported_size_that_may_already_be_trimmed() {
        store = new JsonlSessionStoreImpl(root, false, Clock.fixed(T0, ZoneOffset.UTC));
        session = store.create(cwd);
        AssistantMessage measured = new AssistantMessage(List.of(new ContentBlock.Text("done")), "fake", "m",
            new Usage(1_600L, 10L, 0L, 0L), StopReasonEnum.STOP, Optional.empty(), T0);
        persist(user("go"), call("c1"), big("c1", "read", 80), call("c2"), big("c2", "read", 80), say("ok"),
            user("next"), call("c3"), result("c3"), call("c4"), result("c4"), call("c5"), result("c5"), measured,
            user("more"));
        FakeSummarizerImpl sum = new FakeSummarizerImpl(false);
        List<Message> in = List.copyOf(context);
        CompactingHooks resumed = hooks(sum);
        long view = new CharsTokenEstimatorImpl().estimate(resumed.projected(in, model(1_500)), List.of(), "");
        assertThat(view).as("estimate of the view").isGreaterThan(1_500L);
        assertThat(resumed.transformContext(in, state(1_500))).isNotSameAs(in);
        assertThat(sum.calls).as("nothing was subtracted from the estimate of the view").hasSize(1);
    }

    @Test
    void reset_forgets_the_tight_projection_and_the_pause() {
        store = new JsonlSessionStoreImpl(root, false, Clock.fixed(T0, ZoneOffset.UTC));
        session = store.create(cwd);
        persist(user("go"), call("c1"), big("c1", "read", 80), say("ok"), user("next"), call("c3"),
            big("c3", "read", 80), call("c4"), result("c4"), call("c5"), result("c5"), say("done"));
        FakeSummarizerImpl failing = new FakeSummarizerImpl(true);
        CompactingHooks hooks = hooks(failing);
        List<Message> in = List.copyOf(context);
        hooks.prepareRequest(request(in, 20), state(20));
        hooks.transformContext(in, state(20));
        hooks.transformContext(in, state(20));
        assertThat(failing.calls).as("paused after the failure").hasSize(1);
        hooks.reset();
        hooks.transformContext(in, state(20));
        assertThat(failing.calls).as("a new session is not paused").hasSize(2);
        hooks.prepareRequest(request(in, 20), state(20));
        assertThat(hooks.projected(in, model(1_500)).get(6)).as("tight before reset").isNotSameAs(in.get(6));
        hooks.reset();
        assertThat(hooks.projected(in, model(1_500)).get(6)).as("default projection after reset").isSameAs(in.get(6));
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
