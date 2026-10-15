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

import dev.pai.core.loop.AgentConfig;
import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.AgentLoop;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Message;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.UserMessage;

import static dev.pai.core.compaction.CompactionPolicyTest.call;
import static dev.pai.core.compaction.CompactionPolicyTest.model;
import static dev.pai.core.compaction.CompactionPolicyTest.result;
import static dev.pai.core.compaction.CompactionPolicyTest.say;
import static dev.pai.core.compaction.CompactionPolicyTest.user;
import static dev.pai.core.compaction.MicroCompactorTest.big;
import static org.assertj.core.api.Assertions.assertThat;

/** The compaction stages run by a real loop that persists like the host: the events only the loop emits. */
class CompactionInTheLoopTest {

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
    private final List<ChatRequest> requests = new ArrayList<>();

    private void persist(Message m) {
        leaf = Optional.of(store.append(session, new SessionEntry.MessageEntry("", leaf, T0, m)));
    }

    /** Two early tool results of about 1000 tokens each, then small turns (as in CompactingHooksMicroTest). */
    private CompactingHooks setUp(FakeSummarizerImpl sum) {
        store = new JsonlSessionStoreImpl(root, false, Clock.fixed(T0, ZoneOffset.UTC));
        session = store.create(cwd);
        for (Message m : List.of(user("go"), call("c1"), big("c1", "read", 80), call("c2"), big("c2", "read", 80),
            say("ok"), user("next"), call("c3"), result("c3"), call("c4"), result("c4"), call("c5"), result("c5"),
            say("done"))) {
            persist(m);
            context.add(m);
        }
        return new CompactingHooks(new CharsTokenEstimatorImpl(), new CompactionPolicy(0, 0, 3), sum, store,
            new CompactingHooks.Target(() -> session));
    }

    private void prompt(CompactingHooks compaction, int window, String text) {
        FakeProviderImpl provider = new FakeProviderImpl("fake", req -> {
            requests.add(req);
            return FakeProviderImpl.reply(List.of(new ContentBlock.Text("fine")), StopReasonEnum.STOP);
        });
        AgentConfig cfg = AgentConfig.of(model(window), "").withStages(compaction.stages());
        AgentLoop loop =
            new AgentLoop(provider, new ToolRegistry(), new AgentHooks() {}, cfg, cwd).withLeaf(() -> leaf);
        List<Message> added = loop.run(List.copyOf(context), UserMessage.text(text), e -> {
            events.add(e);
            if (e instanceof AgentEvent.MessageEnd end) {
                persist(end.message());
            } else if (e instanceof AgentEvent.ContextCompacted c
                && c.kind() == AgentEvent.ContextCompacted.KindEnum.SUMMARY) {
                leaf = store.leafId(session);
            }
        }, CancelToken.none());
        context.addAll(added);
    }

    private List<AgentEvent.ContextCompacted> compactions() {
        return events.stream().filter(AgentEvent.ContextCompacted.class::isInstance)
            .map(AgentEvent.ContextCompacted.class::cast).toList();
    }

    @Test
    void micro_compaction_emits_one_micro_event_per_request_and_records_nothing() {
        FakeSummarizerImpl sum = new FakeSummarizerImpl(false);
        CompactingHooks compaction = setUp(sum);
        prompt(compaction, 1_500, "more");
        int n = context.size() - 1;
        assertThat(compactions()).containsExactly(
            new AgentEvent.ContextCompacted(n, n, AgentEvent.ContextCompacted.KindEnum.MICRO, Optional.empty()));
        assertThat(((ToolResultMessage)requests.get(0).messages().get(2)).content().toString())
            .contains("trimmed by pai");
        assertThat(sum.calls).isEmpty();
        assertThat(store.readAll(session)).noneMatch(SessionEntry.CompactionEntry.class::isInstance);
    }

    @Test
    void still_over_the_threshold_after_trimming_summarizes_once_and_reports_the_summary() {
        CompactingHooks compaction = setUp(new FakeSummarizerImpl(false));
        prompt(compaction, 20, "more");
        List<AgentEvent.ContextCompacted> summaries =
            compactions().stream().filter(c -> c.kind() == AgentEvent.ContextCompacted.KindEnum.SUMMARY).toList();
        assertThat(summaries).singleElement().satisfies(e -> {
            assertThat(e.summary()).hasValueSatisfying(s -> assertThat(s).startsWith("summary of"));
            assertThat(e.messagesAfter()).isEqualTo(requests.get(0).messages().size());
        });
        assertThat(store.readAll(session)).filteredOn(SessionEntry.CompactionEntry.class::isInstance).hasSize(1);
        List<Message> kept = store.buildContext(session, leaf.orElseThrow());
        assertThat(kept.get(0)).isInstanceOf(UserMessage.class);
        assertThat(kept.subList(kept.size() - 2, kept.size())).extracting(Object::getClass)
            .containsExactly(UserMessage.class, dev.pai.model.AssistantMessage.class);
    }

    @Test
    void below_the_trigger_share_nothing_is_reported_and_the_request_is_the_context() {
        CompactingHooks compaction = setUp(new FakeSummarizerImpl(false));
        prompt(compaction, 100_000, "more");
        assertThat(compactions()).isEmpty();
        assertThat(requests.get(0).messages()).isEqualTo(context.subList(0, context.size() - 1));
    }
}
