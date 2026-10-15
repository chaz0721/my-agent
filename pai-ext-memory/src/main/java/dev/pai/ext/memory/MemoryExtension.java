/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.loop.AgentEvent;

/**
 * Explicit memory: the {@code remember} and {@code recall} tools, the {@code /memory} command and a {@code <memory>}
 * section in the system prompt. Stored as hand-editable {@code MEMORY.md} files under {@code <paiHome>/memory/} (see
 * {@link MemoryStore}); no embeddings. The {@code ## Decisions} of compaction summaries become candidates that the user
 * reviews when the session ends (or with {@code /memory review}); nothing is stored without a yes.
 */
public final class MemoryExtension implements Extension {

    /** Extension id. */
    public static final String ID = "memory";

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
        ctx.onSession(SessionEvent.SessionStarted.class, e -> injection.sessionStarted());
        MemoryCandidates candidates = new MemoryCandidates();
        ctx.on(AgentEvent.ContextCompacted.class,
            e -> ((AgentEvent.ContextCompacted)e).summary().ifPresent(candidates::offer));
        Supplier<String> review = () -> review(ctx, store, candidates);
        ctx.onSession(SessionEvent.SessionEnded.class, e -> {
            if (!candidates.pending().isEmpty()) {
                ctx.notify(review.get());
            }
        });
        ctx.registerCommand(new MemoryCommand(store, injection, ctx.ui(), review));
    }

    private static String review(ExtensionContext ctx, MemoryStore store, MemoryCandidates candidates) {
        MemoryCandidates.Review r =
            candidates.review(ctx.ui(), c -> store.add(c, List.of(), MemoryStore.PROJECT, Optional.empty()));
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
