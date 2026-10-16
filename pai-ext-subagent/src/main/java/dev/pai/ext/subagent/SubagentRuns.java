/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
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
 * latest request, tie every run to the parent's cancellation, write pending entries and, when {@code spawn_agent} is
 * offered, append the fixed {@link #GUIDANCE} to the system prompt (the same text every request, so the prompt prefix
 * stays cacheable); for sub-agents (which only see the tool-call hooks) they block non-read-only calls of read-only
 * types and of background runs, and take the write-conflict lock of a file-writing call. They must come before the
 * approval hooks in the chain (the host loads this extension before ext-hitl), so that a blocked call is never asked
 * about first.
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
    /** Why a call of a background run that is not read-only does not run. */
    static final String BACKGROUND_BLOCK =
        "background sub-agent cannot ask for approval: run it in the foreground or add an allow rule";
    private static final int NOTICE_ARGUMENT_CHARS = 80;
    /** When to use sub-agents, appended to the parent's system prompt. */
    static final String GUIDANCE = "<sub-agents>\nIf you have spawn_agent: in a large or unfamiliar repo where finding "
        + "code or callers spans many files, use it with agentType explore; for a small question or one file, just "
        + "read. To review a big change, spawn a reviewer.\n</sub-agents>";

    private final ExtensionContext ctx;
    private final Map<String, AgentType> types;
    private final Limits limits;
    private final Semaphore slots;
    private final WriteLocks locks;
    private final Map<String, SubagentRun> runs = new ConcurrentHashMap<>();
    private final Queue<ObjectNode> pending = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<SubagentRun> current = new ThreadLocal<>();
    /** Unknown tool names already reported, as {@code type/tool}. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile Optional<String> parentSession = Optional.empty();
    private volatile Optional<ChatRequest> lastRequest = Optional.empty();
    private volatile CancelToken linked = CancelToken.none();

    SubagentRuns(ExtensionContext ctx, Map<String, AgentType> types, Limits limits) {
        this.ctx = ctx;
        this.types = Map.copyOf(types);
        this.limits = limits;
        this.slots = new Semaphore(limits.maxConcurrency());
        this.locks = new WriteLocks(limits.lockTimeout(), ctx::notify);
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
        type.allowedTools().stream().filter(n -> !AgentType.READ_ONLY.equals(n) && ctx.tool(n).isEmpty())
            .filter(n -> warned.add(type.name() + "/" + n))
            .forEach(n -> ctx.notify("subagent: unknown tool " + n + " in agent type " + type.name()));
        String runId = SideChain.RUN_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, RUN_ID_HEX);
        SubagentRun run = new SubagentRun(runId, type, task, background);
        runs.put(runId, run);
        parentCancel.onCancel(run::cancel);
        SubagentRun.Env env = new SubagentRun.Env(ctx, config, ctx.paiHome().resolve(SESSIONS_DIR), parentSession,
            limits.debug(), this::ended);
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
            locks.releaseAll(run);
            if (slot) {
                slots.release();
            }
        }
    }

    /** Queues the entry of a run that ended (before its waiters wake, so that they find it) and tells the user. */
    private void ended(SubagentRun run) {
        pending.add(run.entry(ctx.paiHome().resolve(SESSIONS_DIR)));
        if (run.background()) {
            ctx.notify(run.label() + " (" + run.type().name() + ") finished: "
                + run.reason().map(r -> r.name().toLowerCase(Locale.ROOT)).orElse("unknown"));
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
            if (request.tools().stream().noneMatch(d -> SubagentTools.SPAWN.equals(d.name()))) {
                return request;
            }
            String system = request.systemPrompt().map(s -> s + "\n\n").orElse("") + GUIDANCE;
            return new ChatRequest(request.model(), Optional.of(system), request.messages(), request.tools(),
                request.options());
        }

        /**
         * Parent calls pass. A sub-agent call that is not read-only for its input is blocked for read-only types and
         * for every background run (nobody can answer an approval for it); a foreground call is announced first, so
         * that an approval prompt after it shows which sub-agent asks; then a file-writing call takes its lock.
         */
        @Override
        public ToolDecision beforeToolCall(ContentBlock.ToolCall call, Tool tool, LoopState state) {
            SubagentRun run = current.get();
            if (run == null) {
                return ToolDecision.allow();
            }
            ToolEffectEnum effect = Tool.effectOf(tool, call.arguments());
            if (effect != ToolEffectEnum.READ_ONLY && run.background()) {
                return ToolDecision.block(BACKGROUND_BLOCK);
            }
            if (effect != ToolEffectEnum.READ_ONLY && run.type().readOnly()) {
                return ToolDecision
                    .block("sub-agent type " + run.type().name() + " is read-only and this " + call.name() + " call is "
                        + effect.name().toLowerCase(Locale.ROOT) + "; use read-only tools and commands only");
            }
            if (effect == ToolEffectEnum.READ_ONLY) {
                return ToolDecision.allow();
            }
            ctx.notify(run.label() + " wants to run " + call.name() + " "
                + SubagentRun.cut(call.arguments().toString(), NOTICE_ARGUMENT_CHARS));
            JsonNode path = call.arguments().path("path");
            if (effect != ToolEffectEnum.WRITE_FS || !path.isTextual()) {
                return ToolDecision.allow();
            }
            CancelToken cancel = state == null ? CancelToken.none() : state.cancel();
            Optional<String> conflict = RealPaths.inside(ctx.cwd(), path.asText())
                .flatMap(p -> locks.acquire(call, p, run, run.label(), cancel::isCancelled));
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
