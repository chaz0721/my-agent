/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.ext.SessionEvent;

/**
 * Sub-agents: the {@code spawn_agent}, {@code agent_result} and {@code agent_cancel} tools, agent types (built-in and
 * {@code ~/.pai/agents/*.md}), side-chain transcripts and write-conflict locks. Options:
 * {@code subagent.max-concurrency} (default {@value #DEFAULT_MAX_CONCURRENCY}), {@code subagent.max-turns} (default
 * {@value #DEFAULT_MAX_TURNS}); the environment variable {@value #DEBUG_ENV}{@code =1} logs every event of every
 * sub-agent.
 */
public final class SubagentExtension implements Extension {

    /** Extension id, also the id the tools are registered under. */
    public static final String ID = "subagent";
    /** Default upper bound of sub-agents running at once. */
    public static final int DEFAULT_MAX_CONCURRENCY = 4;
    /** Default upper bound of turns of one sub-agent run. */
    public static final int DEFAULT_MAX_TURNS = 30;
    /** Environment variable that logs every sub-agent event. */
    public static final String DEBUG_ENV = "PAI_DEBUG_SUBAGENT";
    /** How long a file-writing call waits for another agent writing the same file. */
    public static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);

    private volatile Optional<SubagentRuns> runs = Optional.empty();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onLoad(ExtensionContext ctx) {
        Map<String, AgentType> types = AgentTypes.load(ctx.paiHome().resolve("agents"), ctx::notify);
        boolean debug = "1".equals(System.getenv(DEBUG_ENV)) || ctx.option("subagent.debug").isPresent();
        SubagentRuns r = new SubagentRuns(ctx, types,
            new SubagentRuns.Limits(positive(ctx, "subagent.max-concurrency", DEFAULT_MAX_CONCURRENCY),
                positive(ctx, "subagent.max-turns", DEFAULT_MAX_TURNS), debug, LOCK_TIMEOUT));
        ctx.addHooks(r.hooks());
        ctx.registerTool(new SubagentTools.Spawn(r));
        ctx.registerTool(new SubagentTools.Result(r));
        ctx.registerTool(new SubagentTools.Cancel(r));
        ctx.onSession(SessionEvent.SessionStarted.class,
            e -> r.parentSession(((SessionEvent.SessionStarted)e).id().value()));
        ctx.onSession(SessionEvent.SessionEnded.class, e -> r.close());
        runs = Optional.of(r);
    }

    @Override
    public void onUnload() {
        runs.ifPresent(SubagentRuns::close);
    }

    private static int positive(ExtensionContext ctx, String key, int fallback) {
        Optional<String> v = ctx.option(key);
        if (v.isEmpty()) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(v.get().strip());
            if (n > 0) {
                return n;
            }
        } catch (NumberFormatException e) {
            ctx.logger().debug("{} is not a number", key, e);
        }
        ctx.notify("subagent: ignoring " + key + "=" + v.get() + " (not a positive number); using " + fallback);
        return fallback;
    }
}
