/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.core.compaction.CharsTokenEstimatorImpl;
import dev.pai.core.compaction.Compaction;
import dev.pai.core.compaction.CompactionPolicy;
import dev.pai.core.compaction.ModelSummarizerImpl;
import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.loop.AgentConfig;
import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentLoop;
import dev.pai.core.loop.TerminationReasonEnum;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

/**
 * One sub-agent run: a fresh loop from {@link ExtensionContext#subLoop} that starts from its system prompt and the task
 * alone, records every message in its side chain, reports one line per turn and ends with its last reply as result.
 * {@link #execute} runs on the run's own thread; the other methods may be called from any thread.
 */
final class SubagentRun {

    /** Longest {@code resultSummary} kept in the parent session. */
    static final int MAX_SUMMARY_CHARS = 500;
    private static final int MAX_ARG_CHARS = 60;
    private static final List<String> SHOWN_ARGUMENTS = List.of("path", "pattern", "command", "query");

    private final String runId;
    private final AgentType type;
    private final String task;
    private final boolean background;
    private final CancelTokenSource cancel = new CancelTokenSource();
    private final CountDownLatch done = new CountDownLatch(1);
    private final BlockingQueue<String> progress = new LinkedBlockingQueue<>();
    private volatile String lastLine = "queued";
    private volatile int turns;
    private volatile Usage usage = Usage.ZERO;
    private volatile Optional<TerminationReasonEnum> reason = Optional.empty();
    private volatile String lastError = "";
    private volatile String result = "";
    private volatile Optional<Path> sideFile = Optional.empty();

    SubagentRun(String runId, AgentType type, String task, boolean background) {
        this.runId = runId;
        this.type = type;
        this.task = task;
        this.background = background;
    }

    /** What a run needs from the extension. */
    record Env(ExtensionContext ctx, AgentConfig config, Path sessionsDir, Optional<String> parentSession,
        boolean debug, Consumer<SubagentRun> ended) {
    }

    /**
     * Runs the loop to its end; never throws (a failure becomes the result text). {@link Env#ended()} sees the run
     * before anyone waiting for it is woken.
     */
    void execute(Env env) {
        try {
            SideChain side =
                SideChain.create(env.sessionsDir(), env.parentSession(), runId, type.name(), env.ctx().cwd());
            sideFile = Optional.of(side.file());
            ToolRegistry seen = new ToolRegistry();
            Compaction compaction = new Compaction(new CharsTokenEstimatorImpl(), CompactionPolicy.defaults(),
                new ModelSummarizerImpl(() -> new LoopProviderImpl(env.ctx()), seen), side.store(),
                new Compaction.Target(side::id));
            AgentLoop loop =
                env.ctx().subLoop(env.config().withStages(compaction.stages()), type::offers).withLeaf(side::leaf);
            lastLine = "running";
            loop.run(List.of(), UserMessage.text(task), ev -> onEvent(ev, side, seen, env), cancel);
        } catch (RuntimeException e) {
            env.ctx().logger().warn("sub-agent {} failed", runId, e);
            lastError = String.valueOf(e.getMessage());
            reason = Optional.of(TerminationReasonEnum.HOOK_ERROR);
        } finally {
            lastLine = "finished: " + reason().map(r -> r.name().toLowerCase(Locale.ROOT)).orElse("?");
            try {
                env.ended().accept(this);
            } finally {
                done.countDown();
            }
        }
    }

    private void onEvent(AgentEvent ev, SideChain side, ToolRegistry seen, Env env) {
        if (env.debug()) {
            debug(env.ctx().logger(), ev);
        }
        switch (ev) {
            case AgentEvent.MessageEnd end -> {
                side.append(end.message());
                if (end.message() instanceof AssistantMessage a) {
                    usage = usage.plus(a.usage());
                    result = a.text().strip().isEmpty() ? result : a.text().strip();
                    a.toolCalls().forEach(c -> env.ctx().tool(c.name()).filter(t -> seen.get(c.name()).isEmpty())
                        .ifPresent(t -> seen.register(t, SubagentExtension.ID)));
                }
            }
            case AgentEvent.TurnEnd end -> {
                turns++;
                String line = "[" + runId + " " + type.name() + "] turn " + turns + ": " + describe(end.message());
                lastLine = line;
                progress.add(line);
            }
            case AgentEvent.Error err -> lastError = err.message();
            case AgentEvent.AgentEnd end -> reason = Optional.of(end.reason());
            default -> {
                // streaming and tool events stay in the side chain and the debug log
            }
        }
    }

    private static String describe(AssistantMessage a) {
        if (a.toolCalls().isEmpty()) {
            return a.text().isBlank() ? "(no text)" : "replied";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock.ToolCall c : a.toolCalls()) {
            sb.append(sb.isEmpty() ? "" : "; ").append(c.name());
            SHOWN_ARGUMENTS.stream().map(k -> c.arguments().path(k)).filter(JsonNode::isTextual).findFirst()
                .ifPresent(v -> sb.append(' ').append(cut(v.asText().replaceAll("\\s+", " "), MAX_ARG_CHARS)));
        }
        return sb.toString();
    }

    private void debug(Logger log, AgentEvent ev) {
        String what = switch (ev) {
            case AgentEvent.MessageUpdate u -> u.event().getClass().getSimpleName();
            case AgentEvent.MessageEnd m -> m.message().getClass().getSimpleName();
            case AgentEvent.ToolExecutionStart s -> s.call().name() + " " + cut(s.call().arguments().toString(), 200);
            case AgentEvent.ToolExecutionEnd e -> e.call().name() + (e.result().isError() ? " error" : " ok");
            default -> "";
        };
        log.info("sub-agent {}: {} {}", runId, ev.getClass().getSimpleName(), what);
    }

    /** At most {@code max} code points of {@code s}, the last three replaced by dots when cut. */
    static String cut(String s, int max) {
        if (s.codePointCount(0, s.length()) <= max) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, max - 3)) + "...";
    }

    /** Asks the run to stop; its loop ends at the next check. */
    void cancel() {
        cancel.cancel();
    }

    boolean cancelled() {
        return cancel.isCancelled();
    }

    /** Waits up to {@code millis} for the run to end. */
    boolean await(long millis) throws InterruptedException {
        return done.await(millis, TimeUnit.MILLISECONDS);
    }

    boolean finished() {
        return done.getCount() == 0L;
    }

    /** The next per-turn line, waiting up to {@code millis}; null when none came. */
    String nextProgress(long millis) throws InterruptedException {
        return progress.poll(millis, TimeUnit.MILLISECONDS);
    }

    /** The text the parent model gets once the run ended. */
    String resultText() {
        String head = "[sub-agent " + runId + " (" + type.name() + "): "
            + reason().map(r -> r.name().toLowerCase(Locale.ROOT)).orElse("unknown") + ", " + turns
            + " turns, tokens in " + usage.input() + " out " + usage.output() + "]";
        boolean ok = reason().filter(r -> r == TerminationReasonEnum.COMPLETED).isPresent();
        String body = result.isEmpty() ? "(no reply)" : result;
        return ok ? head + "\n" + body
            : head + "\nerror: " + (lastError.isEmpty() ? "-" : lastError) + "\nlast reply: " + body;
    }

    /** The status of a run still going. */
    String statusText() {
        return "running: " + runId + " (" + type.name() + "), " + turns + " turns so far; last: " + lastLine;
    }

    /** The {@code subagent} entry of the parent session. */
    ObjectNode entry(Path sessionsDir) {
        ObjectNode n = Json.mapper().createObjectNode().put("runId", runId).put("type", type.name()).put("task", task)
            .put("background", background).put("reason", reason().map(Enum::name).orElse("UNKNOWN")).put("turns", turns)
            .put("resultSummary", cut(result, MAX_SUMMARY_CHARS));
        n.putObject("usage").put("input", usage.input()).put("output", usage.output()).put("cacheRead",
            usage.cacheRead());
        sideFile.ifPresent(f -> n.put("sideChain", sessionsDir.relativize(f).toString().replace('\\', '/')));
        return n;
    }

    Optional<TerminationReasonEnum> reason() {
        return reason;
    }

    /** How notices name this run: {@code [sub-agent sa-xxxxxxxx]}. */
    String label() {
        return "[sub-agent " + runId + "]";
    }

    String runId() {
        return runId;
    }

    AgentType type() {
        return type;
    }

    boolean background() {
        return background;
    }
}
