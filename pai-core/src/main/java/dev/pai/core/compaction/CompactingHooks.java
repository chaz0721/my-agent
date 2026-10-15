/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.core.session.SessionTree;
import dev.pai.model.ChatRequest;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;

/**
 * Automatic compaction. Above {@value #MICRO_TRIGGER_PERCENT}% of the policy threshold every request carries the
 * {@link #projected} context (early large tool results replaced by placeholders, nothing recorded, the loop's context
 * untouched). Only when even that projection exceeds the threshold (or the loop reports a context overflow) does
 * {@link #transformContext} summarize: the prefix before {@link CompactionPolicy#splitIndex} is summarized, a
 * {@link SessionEntry.CompactionEntry} is appended to the session and the context becomes the summary message followed
 * by the kept messages, exactly what {@link SessionStore#buildContext} yields afterwards.
 *
 * <p>
 * The context handed to the hook must equal the projection of the branch leaf {@link LoopState#leafEntryId()} reports
 * (the host persists every message before the next request); otherwise, or without a leaf, compaction is skipped. The
 * compaction entry is appended as a child of that leaf, so callers move their leaf to the store's newest entry when the
 * returned list is not the one they passed in. Summarizer failures are logged and the context is returned unchanged. A
 * listener set with {@link #withListener} receives {@link AgentEvent.ContextCompacted} with the summary after each
 * recorded compaction, and with kind {@code MICRO} when the number of trimmed tool results changes.
 */
public final class CompactingHooks implements AgentHooks {

    /** Share of the threshold, in percent, above which requests carry the micro-compacted projection. */
    public static final int MICRO_TRIGGER_PERCENT = 60;
    private static final int PERCENT = 100;

    private static final Logger log = LoggerFactory.getLogger(CompactingHooks.class);

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
    private final Consumer<AgentEvent> listener;
    private volatile int requestOverhead;
    private volatile int trimmed;

    /**
     * Creates the hooks.
     *
     * @param est token estimator
     * @param policy thresholds and boundaries
     * @param sum summarizer
     * @param store session storage that receives compaction entries
     * @param target the active session
     */
    public CompactingHooks(TokenEstimator est, CompactionPolicy policy, Summarizer sum, SessionStore store,
        Target target) {
        this(est, policy, sum, store, target.session(), e -> {
        });
    }

    private CompactingHooks(TokenEstimator est, CompactionPolicy policy, Summarizer sum, SessionStore store,
        Supplier<SessionId> session, Consumer<AgentEvent> listener) {
        this.est = est;
        this.policy = policy;
        this.sum = sum;
        this.store = store;
        this.session = session;
        this.listener = listener;
    }

    /**
     * Returns hooks like these that report compactions to {@code listener}, on the thread that compacts.
     *
     * @param listener receives {@link AgentEvent.ContextCompacted} events
     * @return the new hooks
     */
    public CompactingHooks withListener(Consumer<AgentEvent> listener) {
        return new CompactingHooks(est, policy, sum, store, session, listener);
    }

    /**
     * The session that compaction entries are written to; the branch is {@link LoopState#leafEntryId()}.
     *
     * @param session supplies the active session
     */
    public record Target(Supplier<SessionId> session) {
    }

    @Override
    public List<Message> transformContext(List<Message> messages, LoopState state) {
        return compact(messages, state, state.contextOverflow());
    }

    /**
     * Remembers the size of system prompt and tool definitions, which the context estimate does not see, and sends the
     * {@link #projected} context.
     */
    @Override
    public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
        requestOverhead = est.estimate(List.of(), request.tools(), request.systemPrompt().orElse(""));
        List<Message> view = projected(request.messages(), request.model());
        int n = replaced(request.messages(), view);
        if (n != trimmed) {
            trimmed = n;
            if (n > 0) {
                report(new AgentEvent.ContextCompacted(view.size(), view.size(),
                    AgentEvent.ContextCompacted.KindEnum.MICRO, Optional.empty()));
            }
        }
        if (view == request.messages()) {
            return request;
        }
        return new ChatRequest(request.model(), request.systemPrompt(), view, request.tools(), request.options());
    }

    /**
     * Returns what a request for this context sends: once the estimate exceeds {@value #MICRO_TRIGGER_PERCENT}% of the
     * threshold, the {@link MicroCompactor} projection with the default {@link MicroCompactionPolicy}; when that still
     * exceeds the threshold, the projection that keeps only the results of the latest turn; otherwise the context
     * itself.
     *
     * @param messages the context
     * @param model the model the request goes to
     * @return the projection, or the very same {@code messages} instance
     */
    public List<Message> projected(List<Message> messages, ModelRef model) {
        int threshold = policy.thresholdFor(model);
        if (tokens(messages) * PERCENT <= (long)threshold * MICRO_TRIGGER_PERCENT) {
            return messages;
        }
        List<Message> view = micro.apply(messages, microPolicy);
        return tokens(view) <= threshold ? view : micro.apply(view, tightPolicy);
    }

    private long tokens(List<Message> messages) {
        return (long)est.estimate(messages, List.of(), "") + requestOverhead;
    }

    /** How many elements of the projection differ from the context. */
    private static int replaced(List<Message> messages, List<Message> view) {
        int n = 0;
        for (int i = 0; i < view.size(); i++) {
            n += view.get(i) != messages.get(i) ? 1 : 0;
        }
        return n;
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
        if (!force && (tokens <= threshold || tokens(projected(messages, state.model())) <= threshold)) {
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
            return messages;
        }
        if (state.cancel().isCancelled()) {
            return messages;
        }
        SessionEntry.CompactionEntry entry =
            new SessionEntry.CompactionEntry("", leaf, clock.instant(), summary, firstKept.get(), tokens);
        try {
            store.append(sid, entry);
        } catch (UncheckedIOException e) {
            log.warn("cannot record compaction in session {}; continuing without compaction", sid.value(), e);
            return messages;
        }
        List<Message> out = new ArrayList<>(messages.size() - k + 1);
        out.add(SessionTree.summaryMessage(summary, entry.timestamp()));
        out.addAll(messages.subList(k, messages.size()));
        log.info("compacted {} of {} messages ({} tokens estimated)", k, messages.size(), tokens);
        report(new AgentEvent.ContextCompacted(messages.size(), out.size(),
            AgentEvent.ContextCompacted.KindEnum.SUMMARY, Optional.of(summary)));
        return out;
    }

    private void report(AgentEvent.ContextCompacted event) {
        try {
            listener.accept(event);
        } catch (RuntimeException e) {
            log.warn("compaction listener failed", e);
        }
    }
}
