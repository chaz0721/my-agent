/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.time.Clock;

import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;

/**
 * Explicit memory: the {@code remember} and {@code recall} tools, the {@code /memory} command and a {@code <memory>}
 * section in the system prompt. Stored in {@code <paiHome>/memory/} (see {@link MemoryStore}); no automatic extraction,
 * no embeddings.
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
        MemoryStore store = new MemoryStore(ctx.paiHome(), ctx.cwd(), Clock.systemUTC());
        MemoryInjection injection = new MemoryInjection(store);
        ctx.registerTool(new RememberTool(store));
        ctx.registerTool(new RecallTool(store));
        ctx.addHooks(injection);
        ctx.registerCommand(new MemoryCommand(store, injection, ctx.ui()));
    }
}
