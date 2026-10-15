/* pai-next — Apache-2.0 */
package dev.pai.core.internal;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffects;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ContentBlock;
import dev.pai.model.ToolResultMessage;

/**
 * Executes the tool calls of one assistant message. Preflight (lookup, argument diagnostics, schema check,
 * beforeToolCall) runs for every call, in call order, on the caller thread before anything executes; execution is
 * parallel when every call is concurrency-safe for its input, otherwise sequential. A call reached after cancellation
 * is not executed. Hook exceptions never escape: a failing beforeToolCall blocks, a failing afterToolCall keeps the
 * result. Events from worker threads are relayed so that the sink is only ever invoked on the caller thread.
 */
public final class ToolRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolRunner.class);
    private static final long POLL_MILLIS = 20L;
    private static final String CANCELLED = "cancelled";

    private final ToolRegistry tools;
    private final AgentHooks hooks;
    private final int maxConcurrency;
    private final Path cwd;
    private final Clock clock;

    /**
     * Creates a runner.
     *
     * @param tools the registry
     * @param hooks the hooks
     * @param maxConcurrency parallelism bound
     * @param cwd working directory for tools
     * @param clock time source
     */
    public ToolRunner(ToolRegistry tools, AgentHooks hooks, int maxConcurrency, Path cwd, Clock clock) {
        this.tools = tools;
        this.hooks = hooks;
        this.maxConcurrency = maxConcurrency;
        this.cwd = cwd;
        this.clock = clock;
    }

    /**
     * Runs the calls and returns results in call order.
     *
     * @param reply the assistant message whose tool calls run
     * @param state loop snapshot for hooks
     * @param sink event sink (caller thread only)
     * @param cancel cancellation
     * @return one result message per call, in call order
     */
    public List<ToolResultMessage> run(AssistantMessage reply, LoopState state, Consumer<AgentEvent> sink,
        CancelToken cancel) {
        List<ContentBlock.ToolCall> calls = reply.toolCalls();
        List<Prepared> prepared = new ArrayList<>(calls.size());
        for (ContentBlock.ToolCall call : calls) {
            prepared.add(preflight(call, reply, state));
        }
        boolean parallel = calls.size() > 1 && prepared.stream()
            .allMatch(p -> p.tool().map(t -> ToolEffects.concurrencySafe(t, p.call().arguments())).orElse(true));
        List<ToolResultMessage> results =
            parallel ? runParallel(prepared, state, sink, cancel) : runSequential(prepared, state, sink, cancel);
        return List.copyOf(results);
    }

    private Prepared preflight(ContentBlock.ToolCall call, AssistantMessage reply, LoopState state) {
        Optional<Tool> tool = tools.get(call.name());
        if (tool.isEmpty()) {
            return new Prepared(call, tool, Optional.of("unknown tool: " + call.name()));
        }
        Optional<String> diagnostic = reply.toolCallDiagnostic(call.id());
        if (diagnostic.isPresent()) {
            return new Prepared(call, tool, Optional.of(diagnostic.get() + "; resend the call with valid JSON"));
        }
        Optional<String> invalid = SchemaCheck.validate(tool.get().definition().parametersSchema(), call.arguments());
        if (invalid.isPresent()) {
            return new Prepared(call, tool, invalid);
        }
        ToolDecision d;
        try {
            d = hooks.beforeToolCall(call, tool.get(), state);
        } catch (RuntimeException e) {
            log.warn("beforeToolCall hook failed for {}; blocking the call", call.name(), e);
            d = ToolDecision.block("hook failed: " + e.getMessage());
        }
        Optional<String> blocked =
            d.allowed() ? Optional.empty() : Optional.of("tool call blocked: " + d.reason().orElse("no reason given"));
        return new Prepared(call, tool, blocked);
    }

    private List<ToolResultMessage> runSequential(List<Prepared> prepared, LoopState state, Consumer<AgentEvent> sink,
        CancelToken cancel) {
        List<ToolResultMessage> out = new ArrayList<>();
        for (Prepared p : prepared) {
            out.add(execute(p, state, sink, cancel));
        }
        return out;
    }

    private List<ToolResultMessage> runParallel(List<Prepared> prepared, LoopState state, Consumer<AgentEvent> sink,
        CancelToken cancel) {
        LinkedBlockingQueue<AgentEvent> relay = new LinkedBlockingQueue<>();
        Semaphore permits = new Semaphore(maxConcurrency);
        List<Future<ToolResultMessage>> futures = new ArrayList<>();
        try (ExecutorService pool =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("pai-tool-", 0L).factory())) {
            for (Prepared p : prepared) {
                futures.add(pool.submit(() -> {
                    permits.acquire();
                    try {
                        return execute(p, state, relay::add, cancel);
                    } finally {
                        permits.release();
                    }
                }));
            }
            while (!futures.stream().allMatch(Future::isDone) || !relay.isEmpty()) {
                drain(relay, sink);
            }
            List<ToolResultMessage> out = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                out.add(await(futures.get(i), prepared.get(i)));
            }
            return out;
        }
    }

    private static void drain(LinkedBlockingQueue<AgentEvent> relay, Consumer<AgentEvent> sink) {
        try {
            AgentEvent ev = relay.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            if (ev != null) {
                sink.accept(ev);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for tools", e);
        }
    }

    private ToolResultMessage await(Future<ToolResultMessage> f, Prepared p) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for tools", e);
        } catch (ExecutionException e) {
            log.warn("tool worker failed for {}", p.call().name(), e.getCause());
            return message(p.call(), ToolResult.error("tool failed: " + e.getCause()));
        }
    }

    private ToolResultMessage execute(Prepared p, LoopState state, Consumer<AgentEvent> sink, CancelToken cancel) {
        ContentBlock.ToolCall call = p.call();
        sink.accept(new AgentEvent.ToolExecutionStart(call));
        Instant start = clock.instant();
        ToolResult result;
        if (p.rejection().isPresent()) {
            result = ToolResult.error(p.rejection().get());
        } else if (cancel.isCancelled()) {
            result = ToolResult.error(CANCELLED);
        } else {
            result = invoke(p.tool().orElseThrow(), call, sink, cancel);
        }
        try {
            result = hooks.afterToolCall(call, result, state);
        } catch (RuntimeException e) {
            log.warn("afterToolCall hook failed for {}; keeping the original result", call.name(), e);
        }
        sink.accept(new AgentEvent.ToolExecutionEnd(call, result, Duration.between(start, clock.instant())));
        return message(call, result);
    }

    private ToolResult invoke(Tool tool, ContentBlock.ToolCall call, Consumer<AgentEvent> sink, CancelToken cancel) {
        ToolContext ctx =
            new RunContext(cwd, cancel, partial -> sink.accept(new AgentEvent.ToolExecutionUpdate(call.id(), partial)));
        try {
            return tool.execute(call.arguments(), ctx);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("interrupted");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.error(msg);
        }
    }

    private ToolResultMessage message(ContentBlock.ToolCall call, ToolResult r) {
        return new ToolResultMessage(call.id(), call.name(), r.content(), r.isError(), clock.instant());
    }

    private record Prepared(ContentBlock.ToolCall call, Optional<Tool> tool, Optional<String> rejection) {
    }

    private record RunContext(Path cwd, CancelToken cancel, Consumer<String> progressSink) implements ToolContext {

        @Override
        public void progress(String partial) {
            progressSink.accept(partial);
        }
    }
}
