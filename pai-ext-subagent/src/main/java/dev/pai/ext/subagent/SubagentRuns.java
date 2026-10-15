/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.loop.AgentConfig;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.prompt.ProjectContext;
import dev.pai.core.prompt.SystemPromptBuilder;
import dev.pai.core.tool.RealPaths;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.ModelRef;

/**
 * The sub-agent runs of one host: starts them on their own threads (at most {@code max-concurrency} at a time, the rest
 * wait), cancels them with the parent, and keeps the {@code subagent} entries of finished runs until the parent's loop
 * thread can write them. Its hooks sit in the host chain: for the parent they remember the model and options of the
 * latest request, tie every run to the parent's cancellation and write pending entries; for sub-agents (which only see
 * the tool-call hooks) they block non-read-only calls of read-only types; for everyone they take the write-conflict
 * lock of a file-writing call.
 */
final class SubagentRuns {

    /** Settings from host options. */
    record Limits(int maxConcurrency, int maxTurns, boolean debug, Duration lockTimeout) {
    }

    static final String CUSTOM_TYPE = "subagent";
    private static final String SESSIONS_DIR = "sessions";
    private static final long JOIN_MILLIS = 10_000L;
    private static final long POLL_MILLIS = 200L;
    private static final int RUN_ID_HEX = 8;
    /** Owner of write locks taken by the parent agent. */
    private static final Object PARENT = new Object();

    private final ExtensionContext ctx;
    private final Map<String, AgentType> types;
    private final Limits limits;
    private final Semaphore slots;
    private final WriteLocks locks;
    private final Map<String, SubagentRun> runs = new ConcurrentHashMap<>();
    private final Queue<ObjectNode> pending = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<SubagentRun> current = new ThreadLocal<>();
    private volatile Optional<String> parentSession = Optional.empty();
    private volatile Optional<ChatRequest> lastRequest = Optional.empty();
    private volatile CancelToken linked = CancelToken.none();

    SubagentRuns(ExtensionContext ctx, Map<String, AgentType> types, Limits limits) {
        this.ctx = ctx;
        this.types = Map.copyOf(types);
        this.limits = limits;
        this.slots = new Semaphore(limits.maxConcurrency());
        this.locks = new WriteLocks(limits.lockTimeout());
    }

    Map<String, AgentType> types() {
        return types;
    }

    void parentSession(String id) {
        parentSession = Optional.of(id);
    }

    /**
     * Starts a run of {@code type} on its own thread; the parent's cancellation and the host's end cancel it.
     *
     * @throws IllegalStateException when no model request of the parent was seen yet
     */
    SubagentRun start(AgentType type, String task, boolean background, CancelToken parentCancel) {
        AgentConfig config = config(type);
        String runId = SideChain.RUN_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, RUN_ID_HEX);
        SubagentRun run = new SubagentRun(runId, type, task, background);
        runs.put(runId, run);
        parentCancel.onCancel(run::cancel);
        SubagentRun.Env env =
            new SubagentRun.Env(ctx, config, ctx.paiHome().resolve(SESSIONS_DIR), parentSession, limits.debug());
        Thread.ofPlatform().daemon().name("pai-subagent-" + runId).start(() -> runOnThread(run, env));
        return run;
    }

    /** Waits for a slot (a run cancelled meanwhile still ends through its loop, which stops at once). */
    private void runOnThread(SubagentRun run, SubagentRun.Env env) {
        boolean slot = false;
        try {
            while (!slot && !run.cancelled()) {
                slot = slots.tryAcquire(POLL_MILLIS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.cancel();
        }
        current.set(run);
        try {
            run.execute(env);
        } finally {
            current.remove();
            if (slot) {
                slots.release();
            }
            pending.add(run.entry(env.sessionsDir()));
            if (run.background()) {
                ctx.notify("sub-agent " + run.runId() + " (" + run.type().name() + ") finished: "
                    + run.reason().map(r -> r.name().toLowerCase(Locale.ROOT)).orElse("unknown"));
            }
        }
    }

    private AgentConfig config(AgentType type) {
        ChatRequest last = lastRequest.orElseThrow(
            () -> new IllegalStateException("no model request seen yet, so the model of the sub-agent is unknown"));
        ModelRef m = last.model();
        ModelRef model = type.model().map(id -> new ModelRef(m.providerId(), id, m.baseUrl(), m.contextWindow(),
            m.maxOutputTokens(), m.capabilities())).orElse(m);
        SystemPromptBuilder b = new SystemPromptBuilder().base(SystemPromptBuilder.defaultBase()).section("Environment",
            "Working directory: " + ctx.cwd() + "\nPlatform: " + System.getProperty("os.name"));
        if (type.projectContext()) {
            ProjectContext.load(ctx.paiHome(), ctx.cwd(), ctx.projectTrusted())
                .ifPresent(t -> b.section(ProjectContext.SECTION, t));
        }
        b.section("Sub-agent", type.systemPromptAddendum());
        ChatOptions options = last.options();
        return new AgentConfig(model, b.build(), options, AgentConfig.DEFAULT_MAX_TOOL_CONCURRENCY, limits.maxTurns(),
            true, List.of());
    }

    Optional<SubagentRun> run(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    /** Writes the entries of finished runs to the parent session; call on a thread the host's session may be used. */
    void flush() {
        for (ObjectNode e = pending.poll(); e != null; e = pending.poll()) {
            ctx.appendEntry(CUSTOM_TYPE, e);
        }
    }

    /** Cancels every run. */
    void cancelAll() {
        runs.values().forEach(SubagentRun::cancel);
    }

    /** Cancels every run, waits up to {@value #JOIN_MILLIS} ms for their threads, then writes what finished. */
    void close() {
        cancelAll();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JOIN_MILLIS);
        for (SubagentRun r : runs.values()) {
            try {
                r.await(Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        flush();
    }

    AgentHooks hooks() {
        return new Hooks();
    }

    /** See the class comment. */
    private final class Hooks implements AgentHooks {

        @Override
        public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
            lastRequest = Optional.of(request);
            CancelToken c = state == null ? CancelToken.none() : state.cancel();
            if (c != linked) {
                linked = c;
                c.onCancel(SubagentRuns.this::cancelAll);
            }
            flush();
            return request;
        }

        @Override
        public ToolDecision beforeToolCall(ContentBlock.ToolCall call, Tool tool, LoopState state) {
            SubagentRun run = current.get();
            ToolEffectEnum effect = Tool.effectOf(tool, call.arguments());
            if (run != null && run.type().readOnly() && effect != ToolEffectEnum.READ_ONLY) {
                return ToolDecision
                    .block("sub-agent type " + run.type().name() + " is read-only and this " + call.name() + " call is "
                        + effect.name().toLowerCase(Locale.ROOT) + "; use read-only tools and commands only");
            }
            JsonNode path = call.arguments().path("path");
            if (effect != ToolEffectEnum.WRITE_FS || !path.isTextual()) {
                return ToolDecision.allow();
            }
            Optional<Path> real = RealPaths.inside(ctx.cwd(), path.asText());
            CancelToken cancel = state == null ? CancelToken.none() : state.cancel();
            Optional<String> conflict =
                real.flatMap(p -> locks.acquire(call, p, run == null ? PARENT : run, cancel::isCancelled));
            return conflict.map(ToolDecision::block).orElseGet(ToolDecision::allow);
        }

        @Override
        public ToolResult afterToolCall(ContentBlock.ToolCall call, ToolResult result, LoopState state) {
            locks.release(call);
            return result;
        }
    }

    /** For tests: whether no write lock is held. */
    boolean locksIdle() {
        return locks.idle();
    }

    /** For tests: how many runs were started. */
    int started() {
        return runs.size();
    }
}
