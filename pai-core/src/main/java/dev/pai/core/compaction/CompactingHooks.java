/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.core.session.SessionTree;
import dev.pai.model.ChatRequest;
import dev.pai.model.Message;

/**
 * Automatic compaction in {@link #transformContext}: when the estimated context exceeds the policy threshold (or the
 * loop reports a context overflow), the prefix before {@link CompactionPolicy#splitIndex} is summarized, a
 * {@link SessionEntry.CompactionEntry} is appended to the session and the context becomes the summary message followed
 * by the kept messages, exactly what {@link SessionStore#buildContext} yields afterwards.
 *
 * <p>
 * The context handed to the hook must be the projection of the session's last appended entry (the CLI persists every
 * message before the next request); otherwise compaction is skipped. Summarizer failures are logged and the context is
 * returned unchanged.
 */
public final class CompactingHooks implements AgentHooks {

    private static final Logger log = LoggerFactory.getLogger(CompactingHooks.class);

    private final TokenEstimator est;
    private final CompactionPolicy policy;
    private final Summarizer sum;
    private final SessionStore store;
    private final Supplier<SessionId> session;
    private final Clock clock;
    private volatile int requestOverhead;

    /**
     * Creates the hooks.
     *
     * @param est token estimator
     * @param policy thresholds and boundaries
     * @param sum summarizer
     * @param store session storage that receives compaction entries
     * @param session supplies the active session
     */
    public CompactingHooks(TokenEstimator est, CompactionPolicy policy, Summarizer sum, SessionStore store,
        Supplier<SessionId> session) {
        this(est, policy, sum, store, session, Clock.systemUTC());
    }

    CompactingHooks(TokenEstimator est, CompactionPolicy policy, Summarizer sum, SessionStore store,
        Supplier<SessionId> session, Clock clock) {
        this.est = est;
        this.policy = policy;
        this.sum = sum;
        this.store = store;
        this.session = session;
        this.clock = clock;
    }

    @Override
    public List<Message> transformContext(List<Message> messages, LoopState state) {
        return compact(messages, state, state.contextOverflow());
    }

    /** Remembers the size of system prompt and tool definitions, which the context estimate does not see. */
    @Override
    public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
        requestOverhead = est.estimate(List.of(), request.tools(), request.systemPrompt().orElse(""));
        return request;
    }

    /**
     * Compacts when over the threshold, or unconditionally when forced (the {@code /compact} command and the overflow
     * retry).
     *
     * @param messages the context, the projection of the session leaf
     * @param state loop snapshot (model, cancellation)
     * @param force whether to ignore the threshold
     * @return the new context, or {@code messages} when nothing was compacted
     */
    public List<Message> compact(List<Message> messages, LoopState state, boolean force) {
        long tokens = (long)est.estimate(messages, List.of(), "") + requestOverhead;
        if (!force && tokens <= policy.thresholdFor(state.model())) {
            return messages;
        }
        int k = policy.splitIndex(messages, force);
        SessionId sid = session.get();
        Optional<String> leaf = store.leafId(sid);
        if (k <= 0 || leaf.isEmpty()) {
            log.debug("nothing to compact ({} messages, {} tokens)", messages.size(), tokens);
            return messages;
        }
        SessionTree.Projection p = SessionTree.of(store.readAll(sid)).project(leaf.get());
        Optional<String> firstKept = p.messages().size() == messages.size() ? p.entryIds().get(k) : Optional.empty();
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
        store.append(sid, entry);
        List<Message> out = new ArrayList<>(messages.size() - k + 1);
        out.add(SessionTree.summaryMessage(summary, entry.timestamp()));
        out.addAll(messages.subList(k, messages.size()));
        log.info("compacted {} of {} messages ({} tokens estimated)", k, messages.size(), tokens);
        return out;
    }
}
