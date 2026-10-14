/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.pai.core.internal.LoopSnapshot;
import dev.pai.core.internal.ToolRunner;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatRequest;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.Provider;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

/**
 * The agent loop: stream a reply, execute its tool calls, feed results back, repeat until the model stops calling
 * tools. Pure control flow; everything observable is an {@link AgentEvent} delivered on the calling thread. Tools are
 * only offered when the model has {@link ModelRef.CapabilityEnum#TOOLS}. When the hooks replace the context (another
 * size or any element not identical) a {@link AgentEvent.ContextCompacted} is emitted; a request rejected as too long
 * is retried once with {@link LoopState#contextOverflow()} set, if {@link AgentConfig#retryOnceOnContextOverflow()}.
 */
public final class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** Marker reply: the overflow retry was skipped. */
    private static final AssistantMessage SKIPPED =
        new AssistantMessage(List.of(), "", "", Usage.ZERO, StopReasonEnum.ERROR, Optional.empty(), Instant.EPOCH);

    /** Provider error texts that mean the prompt (input side) exceeded the context window. */
    private static final Pattern CONTEXT_OVERFLOW = Pattern
        .compile("(?i)(prompt is too long|context[ _]length|context window|maximum context length|too many tokens)");

    private final Provider provider;
    private final ToolRegistry tools;
    private final AgentHooks hooks;
    private final AgentConfig config;
    private final Path cwd;
    private final Clock clock;
    private final Supplier<Optional<String>> leaf;

    /**
     * Creates a loop.
     *
     * @param provider model provider
     * @param tools available tools
     * @param hooks extension hooks (use {@link AgentHooks#chain(List)} for several)
     * @param config configuration
     * @param cwd working directory for tools
     */
    public AgentLoop(Provider provider, ToolRegistry tools, AgentHooks hooks, AgentConfig config, Path cwd) {
        this(provider, tools, hooks, config, cwd, Clock.systemUTC());
    }

    AgentLoop(Provider provider, ToolRegistry tools, AgentHooks hooks, AgentConfig config, Path cwd, Clock clock) {
        this(new Parts(provider, tools, hooks, config, cwd), clock, Optional::empty);
    }

    private AgentLoop(Parts p, Clock clock, Supplier<Optional<String>> leaf) {
        this.provider = p.provider();
        this.tools = p.tools();
        this.hooks = p.hooks();
        this.config = p.config();
        this.cwd = p.cwd();
        this.clock = clock;
        this.leaf = leaf;
    }

    /**
     * Returns a copy whose hooks see {@link LoopState#leafEntryId()} from {@code leafEntryId}; the supplier is asked
     * each time a snapshot is taken, on the loop thread, after the host handled every earlier event.
     *
     * @param leafEntryId supplies the session entry the current context is the projection of
     * @return the new loop
     */
    public AgentLoop withLeaf(Supplier<Optional<String>> leafEntryId) {
        return new AgentLoop(new Parts(provider, tools, hooks, config, cwd), clock, leafEntryId);
    }

    /** Constructor arguments shared by the loop's constructors. */
    private record Parts(Provider provider, ToolRegistry tools, AgentHooks hooks, AgentConfig config, Path cwd) {
    }

    /**
     * Appends {@code prompt} to {@code history} and runs until the model replies without tool calls, an error or
     * cancellation occurs, or {@code maxTurns} is reached.
     *
     * @param history existing context
     * @param prompt the new user message
     * @param sink receives every event synchronously
     * @param cancel cooperative cancellation
     * @return every message appended by this run (the prompt, assistant replies and tool results), in order
     */
    public List<Message> run(List<Message> history, UserMessage prompt, Consumer<AgentEvent> sink, CancelToken cancel) {
        Run r = new Run(new ArrayList<>(history), sink);
        sink.accept(new AgentEvent.AgentStart());
        r.append(prompt);
        ToolRunner runner = new ToolRunner(tools, hooks, config.maxToolConcurrency(), cwd, clock);
        for (int turn = 0;; turn++) {
            if (turn >= config.maxTurns()) {
                sink.accept(new AgentEvent.Error("max turns reached: " + config.maxTurns(), Optional.empty()));
                break;
            }
            if (!runTurn(r, turn, runner, cancel)) {
                break;
            }
        }
        sink.accept(new AgentEvent.AgentEnd(r.added));
        return List.copyOf(r.added);
    }

    /** Runs one turn; returns whether another turn should follow. */
    private boolean runTurn(Run r, int turn, ToolRunner runner, CancelToken cancel) {
        r.sink.accept(new AgentEvent.TurnStart(turn));
        Optional<AssistantMessage> reply = requestReply(r, turn, cancel, false);
        if (reply.isEmpty()) {
            return false;
        }
        if (overflowRetryAllowed(r, reply.get())) {
            r.overflowRetried = true;
            r.sink.accept(new AgentEvent.TurnEnd(reply.get(), List.of()));
            r.sink.accept(new AgentEvent.TurnStart(turn));
            AssistantMessage failed = reply.get();
            reply = requestReply(r, turn, cancel, true).map(retried -> retried == SKIPPED ? failed : retried);
            if (reply.isEmpty()) {
                return false;
            }
        }
        AssistantMessage a = reply.get();
        if (!a.replayable()) {
            r.sink.accept(new AgentEvent.TurnEnd(a, List.of()));
            String msg = a.errorMessage().orElse(a.stopReason().name().toLowerCase(Locale.ROOT));
            r.sink.accept(new AgentEvent.Error(msg, Optional.empty()));
            return false;
        }
        List<ToolResultMessage> results =
            a.toolCalls().isEmpty() ? List.of() : runner.run(a, r.state(turn, false, cancel), r.sink, cancel);
        results.forEach(r::append);
        r.sink.accept(new AgentEvent.TurnEnd(a, results));
        List<Message> steering;
        try {
            steering = hooks.steeringMessages();
        } catch (RuntimeException e) {
            log.warn("steeringMessages hook failed; ignoring it", e);
            steering = List.of();
        }
        steering.forEach(r::append);
        return !results.isEmpty() || !steering.isEmpty();
    }

    /**
     * Transforms the context, prepares the request and streams one reply; empty when transformContext failed (an Error
     * event was emitted); {@link #SKIPPED} when an overflow retry would resend an unchanged context.
     */
    private Optional<AssistantMessage> requestReply(Run r, int turn, CancelToken cancel, boolean overflow) {
        List<Message> before = List.copyOf(r.context);
        try {
            r.context = new ArrayList<>(hooks.transformContext(before, r.state(turn, overflow, cancel)));
        } catch (RuntimeException e) {
            r.sink.accept(new AgentEvent.Error("transformContext hook failed: " + e.getMessage(), Optional.of(e)));
            return Optional.empty();
        }
        if (changed(before, r.context)) {
            r.sink.accept(new AgentEvent.ContextCompacted(before.size(), r.context.size()));
        } else if (overflow) {
            log.warn("context overflow: compaction freed nothing, not retrying");
            return Optional.of(SKIPPED);
        }
        List<ToolDefinition> offered =
            config.model().capabilities().contains(ModelRef.CapabilityEnum.TOOLS) ? tools.definitions() : List.of();
        ChatRequest request =
            new ChatRequest(config.model(), Optional.of(config.systemPrompt()), r.context, offered, config.options());
        try {
            request = hooks.prepareRequest(request, r.state(turn, overflow, cancel));
        } catch (RuntimeException e) {
            log.warn("prepareRequest hook failed; sending the unmodified request", e);
        }
        r.sink.accept(new AgentEvent.MessageStart(shell()));
        AssistantMessage reply =
            provider.stream(request, ev -> r.sink.accept(new AgentEvent.MessageUpdate(ev)), cancel);
        r.usage = r.usage.plus(reply.usage());
        r.context.add(reply);
        r.added.add(reply);
        r.sink.accept(new AgentEvent.MessageEnd(reply));
        return Optional.of(reply);
    }

    /** A transformed context counts as compacted when its size or any element (by identity) differs. */
    private static boolean changed(List<Message> before, List<Message> after) {
        if (before.size() != after.size()) {
            return true;
        }
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i) != after.get(i)) {
                return true;
            }
        }
        return false;
    }

    private boolean overflowRetryAllowed(Run r, AssistantMessage reply) {
        return config.retryOnceOnContextOverflow() && !r.overflowRetried && reply.stopReason() == StopReasonEnum.ERROR
            && reply.errorMessage().map(m -> CONTEXT_OVERFLOW.matcher(m).find()).orElse(false);
    }

    private AssistantMessage shell() {
        return new AssistantMessage(List.of(), provider.id(), config.model().modelId(), Usage.ZERO, StopReasonEnum.STOP,
            Optional.empty(), clock.instant());
    }

    /**
     * Mutable state of one run, confined to the calling thread.
     */
    private final class Run {
        private final List<Message> added = new ArrayList<>();
        private final Consumer<AgentEvent> sink;
        private List<Message> context;
        private Usage usage = Usage.ZERO;
        private boolean overflowRetried;

        private Run(List<Message> context, Consumer<AgentEvent> sink) {
            this.context = context;
            this.sink = sink;
        }

        private void append(Message m) {
            context.add(m);
            added.add(m);
            sink.accept(new AgentEvent.MessageStart(m));
            sink.accept(new AgentEvent.MessageEnd(m));
        }

        private LoopState state(int turn, boolean overflow, CancelToken cancel) {
            return new LoopSnapshot(context, config.model(), usage, turn, cwd, overflow, cancel, leaf.get());
        }
    }
}
