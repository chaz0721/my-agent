/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.loop.TerminationReasonEnum;
import dev.pai.core.tool.Args;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.CancelToken;
import dev.pai.model.ToolDefinition;

/**
 * The three tools of the extension: {@code spawn_agent} runs a sub-agent and returns its final reply (or, in the
 * background, its run id at once); {@code agent_result} and {@code agent_cancel} act on a background run.
 */
final class SubagentTools {

    static final String SPAWN = "spawn_agent";
    static final String RESULT = "agent_result";
    static final String CANCEL = "agent_cancel";
    private static final long POLL_MILLIS = 100L;
    /** How long {@code agent_result} waits for a run to end before it reports it as running. */
    static final long RESULT_WAIT_MILLIS = 30_000L;
    /** A foreground run is followed until it ends (it ends on cancellation too). */
    private static final long UNTIL_DONE_MILLIS = Long.MAX_VALUE / 2_000_000L;

    private SubagentTools() {}

    /** Waits for a run while passing its per-turn lines to {@code tc}; false when the wait ended first. */
    private static boolean follow(SubagentRun run, ToolContext tc, long maxMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMillis);
        while (!run.finished() && System.nanoTime() < deadline) {
            String line = run.nextProgress(POLL_MILLIS);
            if (line != null && tc != null) {
                tc.progress(line);
            }
        }
        for (String line = run.nextProgress(0L); line != null; line = run.nextProgress(0L)) {
            if (tc != null) {
                tc.progress(line);
            }
        }
        return run.finished();
    }

    private static CancelToken cancelOf(ToolContext tc) {
        return tc == null ? CancelToken.none() : tc.cancel();
    }

    /** {@code spawn_agent(task, agentType = "general", background = false)}. */
    static final class Spawn implements Tool {
        private final SubagentRuns runs;

        Spawn(SubagentRuns runs) {
            this.runs = runs;
        }

        @Override
        public ToolDefinition definition() {
            String kinds = runs.types().values().stream()
                .map(t -> "- " + t.name() + (t.description().isEmpty() ? "" : ": " + t.description()))
                .collect(Collectors.joining("\n"));
            return new ToolDefinition(SPAWN,
                "Run a sub-agent on a self-contained task in a fresh context; only its final reply comes back, so "
                    + "what it reads does not fill your context. Give it everything it needs in the task. Several "
                    + "calls in one reply run in parallel; background=true returns a run id at once (then use " + RESULT
                    + "). Agent types:\n" + kinds,
                JsonSchema.object().string("task", "The complete task, with paths and what to report back")
                    .string("agentType", "One of the agent types above; default general")
                    .bool("background", "Return at once and run in the background; default false").required("task")
                    .build());
        }

        @Override
        public ToolEffectEnum effect() {
            return ToolEffectEnum.EXECUTE;
        }

        /** Spawning a read-only type (explore, reviewer) is itself read-only, so plan mode may use it. */
        @Override
        public ToolEffectEnum effect(JsonNode input) {
            AgentType t = runs.types().get(input.path("agentType").asText(AgentTypes.DEFAULT_TYPE).strip());
            return t != null && t.readOnly() ? ToolEffectEnum.READ_ONLY : ToolEffectEnum.EXECUTE;
        }

        @Override
        public boolean concurrencySafe() {
            return true;
        }

        @Override
        public boolean concurrencySafe(JsonNode input) {
            return true;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext tc) throws InterruptedException {
            Args a = Args.of(arguments);
            String task = a.string("task").strip();
            String name = a.stringOr("agentType", AgentTypes.DEFAULT_TYPE).strip();
            Optional<AgentType> type = Optional.ofNullable(runs.types().get(name));
            if (task.isEmpty() || type.isEmpty()) {
                return ToolResult.error(task.isEmpty() ? "task is empty"
                    : "unknown agent type: " + name + "; known: " + String.join(", ", runs.types().keySet()));
            }
            SubagentRun run;
            try {
                run = runs.start(type.get(), task, a.boolOr("background", false), cancelOf(tc));
            } catch (IllegalStateException e) {
                return ToolResult.error("sub-agent not started: " + e.getMessage());
            }
            if (run.background()) {
                return ToolResult.text("started " + run.runId() + " (" + name + ") in the background; call " + RESULT
                    + " with this runId for its result");
            }
            follow(run, tc, UNTIL_DONE_MILLIS);
            runs.flush();
            return SubagentTools.result(run);
        }
    }

    private static ToolResult result(SubagentRun run) {
        String text = run.resultText();
        boolean ok = run.reason().filter(r -> r == TerminationReasonEnum.COMPLETED).isPresent();
        return ok ? ToolResult.text(text) : ToolResult.error(text);
    }

    /** {@code agent_result(runId)}: the result of a background run, or that it is still running. */
    static final class Result implements Tool {
        private final SubagentRuns runs;

        Result(SubagentRuns runs) {
            this.runs = runs;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(RESULT,
                "Get the result of a background sub-agent run; waits up to " + RESULT_WAIT_MILLIS / 1000L
                    + "s, then says it is still running.",
                JsonSchema.object().string("runId", "The id spawn_agent returned").required("runId").build());
        }

        @Override
        public ToolEffectEnum effect() {
            return ToolEffectEnum.READ_ONLY;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext tc) throws InterruptedException {
            String id = Args.of(arguments).string("runId").strip();
            Optional<SubagentRun> run = runs.run(id);
            if (run.isEmpty()) {
                return ToolResult.error("no sub-agent run " + id);
            }
            long wait = cancelOf(tc).isCancelled() ? 0L : RESULT_WAIT_MILLIS;
            boolean done = follow(run.get(), tc, wait);
            runs.flush();
            return done ? SubagentTools.result(run.get()) : ToolResult.text(run.get().statusText());
        }
    }

    /** {@code agent_cancel(runId)}: stops a background run. */
    static final class Cancel implements Tool {
        private final SubagentRuns runs;

        Cancel(SubagentRuns runs) {
            this.runs = runs;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(CANCEL, "Stop a background sub-agent run.",
                JsonSchema.object().string("runId", "The id spawn_agent returned").required("runId").build());
        }

        @Override
        public ToolEffectEnum effect() {
            return ToolEffectEnum.READ_ONLY;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext tc) {
            String id = Args.of(arguments).string("runId").strip();
            Optional<SubagentRun> run = runs.run(id);
            if (run.isEmpty()) {
                return ToolResult.error("no sub-agent run " + id);
            }
            run.get().cancel();
            return ToolResult.text(run.get().finished() ? id + " had already finished" : "cancelling " + id);
        }
    }
}
