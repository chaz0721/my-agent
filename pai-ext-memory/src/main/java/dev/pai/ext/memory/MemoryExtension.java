/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.node.ArrayNode;

import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.loop.AgentEvent;
import dev.pai.model.Json;

/**
 * Explicit memory: the {@code remember} and {@code recall} tools, the {@code /memory} command and a {@code <memory>}
 * section in the system prompt. Stored as hand-editable {@code MEMORY.md} files under {@code <paiHome>/memory/} (see
 * {@link MemoryStore}); no embeddings. The {@code ## Decisions} of compaction summaries become candidates that the user
 * reviews when the session ends (or with {@code /memory review}); nothing is stored without a yes.
 */
public final class MemoryExtension implements Extension {

    /** Extension id. */
    public static final String ID = "memory";
    /** Custom entry type recording the candidate keys the user turned down in a session. */
    public static final String REJECTED_TYPE = "memory-rejected";
    private static final String REJECTED_KEYS = "keys";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onLoad(ExtensionContext ctx) {
        MemoryStore store = new MemoryStore(ctx.paiHome(), ctx.cwd());
        try {
            int migrated = store.migrate();
            if (migrated > 0) {
                ctx.notify("memory: moved " + migrated + " memories from the old JSONL files to MEMORY.md (old files "
                    + "kept as " + MemoryMigration.SUFFIX + ")");
            }
        } catch (UncheckedIOException e) {
            ctx.logger().warn("memory migration failed; the old JSONL files are left as they are", e);
            ctx.notify("memory: could not move the old JSONL memories to MEMORY.md: " + e.getMessage());
        }
        MemoryInjection injection = new MemoryInjection(store);
        ctx.registerTool(new RememberTool(store));
        ctx.registerTool(new RecallTool(store));
        ctx.addHooks(injection);
        MemoryCandidates candidates = new MemoryCandidates();
        ctx.onSession(SessionEvent.SessionStarted.class, e -> {
            injection.sessionStarted();
            candidates.sessionStarted(rejectedBefore(ctx));
        });
        ctx.on(AgentEvent.ContextCompacted.class, e -> ((AgentEvent.ContextCompacted)e).summary()
            .ifPresent(summary -> candidates.offer(summary, known(store))));
        Supplier<String> review = () -> review(ctx, store, candidates);
        ctx.onSession(SessionEvent.SessionEnded.class, e -> {
            if (!candidates.pending().isEmpty()) {
                ctx.notify(review.get());
            }
        });
        ctx.registerCommand(new MemoryCommand(store, injection, ctx.ui(), review));
    }

    /** Keys of the remembered facts. */
    private static Set<String> known(MemoryStore store) {
        return store.all().stream().map(e -> MemoryCandidates.key(e.text())).collect(Collectors.toSet());
    }

    /** Keys turned down earlier in this session, as last recorded in it. */
    private static List<String> rejectedBefore(ExtensionContext ctx) {
        List<String> keys = new ArrayList<>();
        ctx.latestEntry(REJECTED_TYPE).ifPresent(e -> e.data().path(REJECTED_KEYS).forEach(k -> keys.add(k.asText())));
        return keys;
    }

    private static String review(ExtensionContext ctx, MemoryStore store, MemoryCandidates candidates) {
        MemoryCandidates.Review r = candidates.review(ctx.ui(), known(store),
            c -> store.add(c, List.of(), MemoryStore.PROJECT, Optional.empty()));
        if (r.newlyRejected() > 0) {
            ArrayNode keys = Json.mapper().createArrayNode();
            candidates.rejected().stream().sorted().forEach(keys::add);
            ctx.appendEntry(REJECTED_TYPE, Json.mapper().createObjectNode().set(REJECTED_KEYS, keys));
        }
        if (r.offered() == 0) {
            return "no memory candidates in this session";
        }
        if (ctx.ui().isEmpty()) {
            return r.offered() + " memory candidates from this session not saved (no terminal to review them)";
        }
        String edit = r.editable() > 0 ? "; reword them in " + store.file(MemoryStore.PROJECT) : "";
        return "memory: saved " + r.saved() + " of " + r.offered() + " candidates" + edit;
    }
}
