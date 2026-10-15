/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.ContextStage;
import dev.pai.core.loop.LoopState;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.core.session.SessionTree;
import dev.pai.model.AssistantMessage;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

/**
 * Automatic compaction, run by the loop as the two {@link #stages()}. Above {@value #MICRO_TRIGGER_PERCENT}% of the
 * threshold requests carry the {@link #projected} context (early large tool results as placeholders; nothing recorded,
 * the kept context untouched). Only when even that exceeds the threshold (or the loop reports an overflow) does
 * {@link #compact} summarize the prefix before {@link CompactionPolicy#splitIndex}, append a
 * {@link SessionEntry.CompactionEntry} and return what {@link SessionStore#buildContext} yields afterwards.
 *
 * <p>
 * The context must equal the projection of the leaf {@link LoopState#leafEntryId()} reports, otherwise nothing is
 * compacted; entries go below that leaf, so callers move their leaf to the store's newest entry when the returned list
 * is not the one they passed in. The tighter projection, once needed, is kept until the next summary. A failed summary
 * pauses automatic compaction for {@value #RETRY_AFTER_TURNS} turns; {@value #MAX_FAILURES} in a row (a summary that
 * leaves the context over the threshold counts too) stop it until a forced compaction brings it below ({@code force} is
 * affected by neither).
 */
public final class Compaction {

    /** Share of the threshold, in percent, above which requests carry the micro-compacted projection. */
    public static final int MICRO_TRIGGER_PERCENT = 60;
    /** Turns without automatic compaction after a summary failed. */
    public static final int RETRY_AFTER_TURNS = 3;
    /** Failed summaries in a row after which automatic compaction stops. */
    public static final int MAX_FAILURES = 3;
    private static final int PERCENT = 100;

    private static final Logger log = LoggerFactory.getLogger(Compaction.class);

    private final TokenEstimator est;
    private final CompactionPolicy policy;
    private final Summarizer sum;
    private final SessionStore store;
    private final Supplier<SessionId> session;
    private final Clock clock = Clock.systemUTC();
    private final MicroCompactor micro = new MicroCompactor();
    private final MicroCompactionPolicy microPolicy = MicroCompactionPolicy.defaults();
    private final MicroCompactionPolicy tightPolicy =
        new MicroCompactionPolicy(1, microPolicy.maxToolResultChars(), microPolicy.neverTrim());
    private final Host host;
    private volatile int requestOverhead;
    /** Call ids of the tool results the last sent request carried as placeholders. */
    private volatile Set<String> sentTrimmed = Set.of();
    /** Call ids the projection of the request being prepared replaces; sent ones become {@link #sentTrimmed}. */
    private volatile Set<String> pendingTrimmed = Set.of();
    private volatile int failures;
    /** Whether {@link #sentTrimmed} was set by a request of this instance (not so after a start or resume). */
    private volatile boolean sentKnown;
    private volatile boolean tight;
    private volatile int pausedTurns;
    /**
     * Assistant messages the last summary kept: the size they report was measured on the context before it, so the
     * estimate ignores it until a newer reply reports one.
     */
    private volatile Set<AssistantMessage> staleUsage = Set.of();

    /**
     * Creates automatic compaction.
     *
     * @param est token estimator
     * @param policy thresholds and boundaries
     * @param sum summarizer
     * @param store session storage that receives compaction entries
     * @param target the active session
     */
    public Compaction(TokenEstimator est, CompactionPolicy policy, Summarizer sum, SessionStore store, Target target) {
        this(est, policy, sum, store, target.session(), new Host(e -> {
        }, (paths, room) -> Optional.empty()));
    }

    private Compaction(TokenEstimator est, CompactionPolicy policy, Summarizer sum, SessionStore store,
        Supplier<SessionId> session, Host host) {
        this.est = est;
        this.policy = policy;
        this.sum = sum;
        this.store = store;
        this.session = session;
        this.host = host;
    }

    /**
     * Returns compaction like this one that tells the user when automatic compaction stops and, after each summary,
     * asks {@code restore} for the current content of files the summary lists as touched: that text, when there is one,
     * becomes a user message recorded below the compaction entry and ending the new context.
     *
     * @param notices receives one line of text
     * @param restore gets the paths of {@code ## Files touched}, in order, and the tokens the text may take (half the
     *            room left below the threshold); returns the message text or empty
     * @return the new instance
     */
    public Compaction withHost(Consumer<String> notices, BiFunction<List<String>, Integer, Optional<String>> restore) {
        return new Compaction(est, policy, sum, store, session, new Host(notices, restore));
    }

    /** Callbacks into the host. */
    private record Host(Consumer<String> notices, BiFunction<List<String>, Integer, Optional<String>> restore) {
    }

    /**
     * Returns the stages for {@code AgentConfig.withStages}: micro compaction, then the summary.
     *
     * @return the two stages, in order
     */
    public List<ContextStage> stages() {
        return List.of(new MicroCompactStage(), new SummaryCompactStage());
    }

    /**
     * The session that compaction entries are written to; the branch is {@link LoopState#leafEntryId()}.
     *
     * @param session supplies the active session
     */
    public record Target(Supplier<SessionId> session) {
    }

    /**
     * Forgets what was learned about the previous session (trimmed results, tighter projection, pause after a failed
     * summary); the host calls it when it switches to another session.
     */
    public void reset() {
        sentTrimmed = Set.of();
        sentKnown = false;
        tight = false;
        pausedTurns = 0;
        failures = 0;
        pendingTrimmed = Set.of();
        staleUsage = Set.of();
    }

    /** The micro stage: the projection for the request being prepared; the tighter policy sticks once used. */
    List<Message> project(List<Message> messages, ModelRef model) {
        Projection p = projection(messages, model);
        tight |= p.tight();
        pendingTrimmed = trimmedIds(messages, p.view());
        return p.view();
    }

    /**
     * Remembers what the request finally sent: the size of system prompt and tool definitions (which the context
     * estimate does not see) and the results it carried as placeholders.
     */
    void sent(ChatRequest request) {
        requestOverhead = est.estimate(List.of(), request.tools(), request.systemPrompt().orElse(""));
        sentTrimmed = pendingTrimmed;
        sentKnown = true;
    }

    /**
     * Returns what a request for this context sends now: above {@value #MICRO_TRIGGER_PERCENT}% of the threshold the
     * {@link MicroCompactor} projection, with only the latest turn's results kept when the default policy is (or was,
     * since the last summary) not enough. Read-only: the micro stage uses it too and remembers what it sent.
     *
     * @param messages the context
     * @param model the model the request goes to
     * @return the projection, or the very same {@code messages} instance
     */
    public List<Message> projected(List<Message> messages, ModelRef model) {
        return projection(messages, model).view();
    }

    /** A projection and whether it needed the tighter policy. */
    private record Projection(List<Message> view, boolean tight) {
    }

    private Projection projection(List<Message> messages, ModelRef model) {
        int threshold = policy.thresholdFor(model);
        if (tokens(messages) * PERCENT <= (long)threshold * MICRO_TRIGGER_PERCENT) {
            return new Projection(messages, false);
        }
        List<Message> view = micro.apply(messages, microPolicy);
        if (!tight && tokens(messages, view) <= threshold) {
            return new Projection(view, false);
        }
        return new Projection(micro.apply(view, tightPolicy), true);
    }

    private long tokens(List<Message> messages) {
        Set<AssistantMessage> stale = staleUsage;
        List<Message> counted = messages;
        if (!stale.isEmpty()) {
            counted = new ArrayList<>(messages.size());
            for (Message m : messages) {
                counted.add(m instanceof AssistantMessage a && stale.contains(a) ? uncalibrated(List.of(a)).get(0) : m);
            }
        }
        return (long)est.estimate(counted, List.of(), "") + requestOverhead;
    }

    /**
     * Estimated size of a request carrying {@code view} instead of {@code messages}. The estimator is calibrated by the
     * size the provider reported for the last reply, and that request already carried the results trimmed then
     * ({@link #sentTrimmed}); so the estimate of the view is reduced only by what the results trimmed now for the first
     * time save, and never below the plain character estimate of the view. Before this instance sent a request (after a
     * start or a resume the last reply may already have been measured on a trimmed request) nothing is subtracted: at
     * worst that compacts once more than needed.
     */
    private long tokens(List<Message> messages, List<Message> view) {
        long direct = tokens(view);
        if (!sentKnown) {
            return direct;
        }
        List<Message> before = new ArrayList<>();
        List<Message> after = new ArrayList<>();
        for (int i = 0; i < view.size(); i++) {
            if (view.get(i) != messages.get(i) && view.get(i) instanceof ToolResultMessage r
                && !sentTrimmed.contains(r.toolCallId())) {
                before.add(messages.get(i));
                after.add(view.get(i));
            }
        }
        if (before.isEmpty()) {
            return direct;
        }
        long saved = (long)est.estimate(before, List.of(), "") - est.estimate(after, List.of(), "");
        return Math.max(direct - saved, (long)est.estimate(uncalibrated(view), List.of(), "") + requestOverhead);
    }

    /** The messages with the reported usage removed, so that the estimate counts characters only. */
    private static List<Message> uncalibrated(List<Message> messages) {
        List<Message> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            out.add(m instanceof AssistantMessage a ? new AssistantMessage(a.content(), a.providerId(), a.modelId(),
                Usage.ZERO, a.stopReason(), a.errorMessage(), a.timestamp(), a.toolCallDiagnostics()) : m);
        }
        return out;
    }

    /** Call ids of the tool results that the projection replaced. */
    private static Set<String> trimmedIds(List<Message> messages, List<Message> view) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < view.size(); i++) {
            if (view.get(i) != messages.get(i) && view.get(i) instanceof ToolResultMessage r) {
                ids.add(r.toolCallId());
            }
        }
        return Set.copyOf(ids);
    }

    /**
     * Compacts when over the threshold, or unconditionally when forced (the {@code /compact} command and the overflow
     * retry).
     *
     * @param messages the context, the projection of the session leaf
     * @param state loop snapshot (model, cancellation, session leaf)
     * @param force whether to ignore the threshold
     * @return the new context, or the very same {@code messages} instance when nothing was compacted
     */
    public List<Message> compact(List<Message> messages, LoopState state, boolean force) {
        long tokens = tokens(messages);
        int threshold = policy.thresholdFor(state.model());
        if (!force && (tokens <= threshold || tokens(messages, projected(messages, state.model())) <= threshold)) {
            return messages;
        }
        if (!force && failures >= MAX_FAILURES) {
            return messages;
        }
        if (!force && pausedTurns > 0) {
            pausedTurns--;
            log.debug("automatic compaction paused for {} more turns after a failed summary", pausedTurns);
            return messages;
        }
        int k = policy.splitIndex(messages, force);
        SessionId sid = session.get();
        Optional<String> leaf = state.leafEntryId();
        if (k <= 0 || leaf.isEmpty()) {
            log.debug("nothing to compact ({} messages, {} tokens)", messages.size(), tokens);
            return messages;
        }
        SessionTree.Projection p = SessionTree.of(store.readAll(sid)).project(leaf.get());
        Optional<String> firstKept = p.messages().equals(messages) ? p.entryIds().get(k) : Optional.empty();
        if (firstKept.isEmpty()) {
            log.warn("context does not match session {} leaf {}; skipping compaction", sid.value(), leaf.get());
            return messages;
        }
        String summary;
        try {
            summary = sum.summarize(List.copyOf(messages.subList(0, k)), state, state.cancel());
        } catch (RuntimeException e) {
            log.warn("summarizing {} messages failed; continuing without compaction", k, e);
            pausedTurns = force ? pausedTurns : RETRY_AFTER_TURNS;
            failed(force);
            return messages;
        }
        if (state.cancel().isCancelled()) {
            return messages;
        }
        SessionEntry.CompactionEntry entry =
            new SessionEntry.CompactionEntry("", leaf, clock.instant(), summary, firstKept.get(), tokens);
        String entryId;
        try {
            entryId = store.append(sid, entry);
        } catch (UncheckedIOException e) {
            log.warn("cannot record compaction in session {}; continuing without compaction", sid.value(), e);
            return messages;
        }
        List<Message> out = new ArrayList<>(messages.size() - k + 2);
        out.add(SessionTree.summaryMessage(summary, entry.timestamp()));
        out.addAll(messages.subList(k, messages.size()));
        Set<AssistantMessage> kept = new HashSet<>();
        for (Message m : out) {
            if (m instanceof AssistantMessage a) {
                kept.add(a);
            }
        }
        staleUsage = Set.copyOf(kept);
        long after = tokens(out);
        restored(SummaryValidator.files(summary), (int)Math.max(0L, (threshold - after) / 2), sid, entryId)
            .ifPresent(out::add);
        log.info("compacted {} of {} messages ({} tokens estimated, {} after)", k, messages.size(), tokens, after);
        tight = false;
        pausedTurns = 0;
        if (after > threshold) {
            failed(force);
        } else {
            failures = 0;
        }
        return out;
    }

    /** A summary failed or left the context over the threshold; automatic ones count towards the stop. */
    private void failed(boolean force) {
        if (!force && ++failures == MAX_FAILURES) {
            host.notices().accept("automatic compaction stopped after " + MAX_FAILURES + " summaries that failed or "
                + "left the context too large; /compact still works and turns it back on");
        }
    }

    /** The restored files after a summary, recorded below the compaction entry; empty when there are none. */
    private Optional<Message> restored(List<String> files, int room, SessionId sid, String parent) {
        Optional<String> text = files.isEmpty() ? Optional.empty() : host.restore().apply(files, room);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        UserMessage m = new UserMessage(List.of(new ContentBlock.Text(text.get())), clock.instant());
        try {
            store.append(sid, new SessionEntry.MessageEntry("", Optional.of(parent), m.timestamp(), m));
            return Optional.of(m);
        } catch (UncheckedIOException e) {
            log.warn("cannot record the restored files in session {}; continuing without them", sid.value(), e);
            return Optional.empty();
        }
    }

    /** Replaces early large tool results with placeholders in the request only ({@link #projected}). */
    private final class MicroCompactStage implements ContextStage {

        @Override
        public String name() {
            return "micro-compact";
        }

        @Override
        public List<Message> apply(List<Message> messages, LoopState state) {
            return project(messages, state.model());
        }

        @Override
        public AgentEvent.ContextCompacted.KindEnum kind() {
            return AgentEvent.ContextCompacted.KindEnum.MICRO;
        }

        @Override
        public void requestSent(ChatRequest request) {
            sent(request);
        }
    }

    /** Summarizes the kept context when even the projection it gets is too big ({@link #compact}). */
    private final class SummaryCompactStage implements ContextStage {

        @Override
        public String name() {
            return "summary-compact";
        }

        @Override
        public List<Message> apply(List<Message> messages, LoopState state) {
            List<Message> kept = state.messages();
            List<Message> out = compact(kept, state, state.contextOverflow());
            return out == kept ? messages : out;
        }

        @Override
        public Optional<String> summaryOf(List<Message> result) {
            return summary(result);
        }
    }

    /**
     * Returns the summary text a context made by a compaction starts with.
     *
     * @param context the context
     * @return the summary, empty when the context does not start with one
     */
    public static Optional<String> summary(List<Message> context) {
        String prefix = JsonlSessionStoreImpl.SUMMARY_PREFIX;
        return context.isEmpty() || !(context.get(0) instanceof UserMessage u) ? Optional.empty()
            : u.content().stream().filter(ContentBlock.Text.class::isInstance).map(b -> ((ContentBlock.Text)b).text())
                .filter(t -> t.startsWith(prefix)).map(t -> t.substring(prefix.length())).findFirst();
    }
}
