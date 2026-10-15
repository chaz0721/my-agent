/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.pai.core.compaction.CharsTokenEstimatorImpl;
import dev.pai.core.compaction.TokenEstimator;
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
 * only offered when the model has {@link ModelRef.CapabilityEnum#TOOLS}. Each request passes the transformContext
 * hooks, then {@link AgentConfig#stages()}; a context the hooks replace (another size or element) or a stage that takes
 * effect emits one {@link AgentEvent.ContextCompacted}. A request rejected as too long is retried once with
 * {@link LoopState#contextOverflow()} set (if configured); cut-off tool calls get {@value #MAX_OUTPUT_RETRIES} retries
 * in a row.
 */
public final class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** Marker reply: the overflow retry was skipped. */
    private static final AssistantMessage SKIPPED =
        new AssistantMessage(List.of(), "", "", Usage.ZERO, StopReasonEnum.ERROR, Optional.empty(), Instant.EPOCH);

    /** Provider error texts that mean the prompt (input side) exceeded the context window. */
    private static final Pattern CONTEXT_OVERFLOW = Pattern
        .compile("(?i)(prompt is too long|context[ _]length|context window|maximum context length|too many tokens)");
    /** Retries after a reply with tool calls was cut off at the output token limit. */
    private static final int MAX_OUTPUT_RETRIES = 2;
    private static final String CONTINUE = "Your previous reply was cut off at the output token limit. Continue "
        + "exactly where it stopped and resend any tool call that was cut off; keep each call small.";
    private static final TokenEstimator ESTIMATOR = new CharsTokenEstimatorImpl();

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
     * cancellation occurs, or {@code maxTurns} is reached; {@link AgentEvent.AgentEnd#reason()} says which.
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
        Optional<TerminationReasonEnum> end = Optional.empty();
        for (int turn = 0; end.isEmpty(); turn++) {
            if (turn >= config.maxTurns()) {
                sink.accept(new AgentEvent.Error("max turns reached: " + config.maxTurns(), Optional.empty()));
                end = Optional.of(TerminationReasonEnum.MAX_TURNS);
            } else {
                end = runTurn(r, turn, runner, cancel);
            }
        }
        sink.accept(new AgentEvent.AgentEnd(r.added, end.get()));
        return List.copyOf(r.added);
    }

    /** Runs one turn; returns why the run ends, empty when another turn should follow. */
    private Optional<TerminationReasonEnum> runTurn(Run r, int turn, ToolRunner runner, CancelToken cancel) {
        Optional<AssistantMessage> reply = requestReply(r, turn, cancel, false);
        if (reply.isPresent() && overflowRetryAllowed(r, reply.get())) {
            r.overflowRetried = true;
            r.sink.accept(new AgentEvent.TurnEnd(reply.get(), List.of()));
            AssistantMessage failed = reply.get();
            reply = requestReply(r, turn, cancel, true).map(retried -> retried == SKIPPED ? failed : retried);
        }
        if (reply.isEmpty()) {
            return Optional.of(TerminationReasonEnum.HOOK_ERROR);
        }
        AssistantMessage a = reply.get();
        if (!a.replayable()) {
            r.sink.accept(new AgentEvent.TurnEnd(a, List.of()));
            String msg = a.errorMessage().orElse(a.stopReason().name().toLowerCase(Locale.ROOT));
            r.sink.accept(new AgentEvent.Error(msg, Optional.empty()));
            return Optional.of(failure(a, cancel));
        }
        List<ToolResultMessage> results =
            a.toolCalls().isEmpty() ? List.of() : runner.run(a, r.state(turn, false, cancel), r.sink, cancel);
        results.forEach(r::append);
        r.sink.accept(new AgentEvent.TurnEnd(a, results));
        boolean cut = a.stopReason() == StopReasonEnum.LENGTH && !a.toolCalls().isEmpty();
        if (cut && r.lengthRetries == MAX_OUTPUT_RETRIES) {
            r.sink.accept(new AgentEvent.Error(
                "reply cut off at the output token limit " + (MAX_OUTPUT_RETRIES + 1) + " times", Optional.empty()));
            return Optional.of(TerminationReasonEnum.MAX_OUTPUT_TOKENS);
        }
        r.lengthRetries = cut ? r.lengthRetries + 1 : 0;
        if (cut) {
            r.append(UserMessage.text(CONTINUE));
        }
        List<Message> steering;
        try {
            steering = hooks.steeringMessages();
        } catch (RuntimeException e) {
            log.warn("steeringMessages hook failed; ignoring it", e);
            steering = List.of();
        }
        steering.forEach(r::append);
        boolean more = cut || !results.isEmpty() || !steering.isEmpty();
        return more ? Optional.empty() : Optional.of(TerminationReasonEnum.COMPLETED);
    }

    private static TerminationReasonEnum failure(AssistantMessage a, CancelToken cancel) {
        if (a.stopReason() == StopReasonEnum.ABORTED || cancel.isCancelled()) {
            return TerminationReasonEnum.ABORTED;
        }
        boolean tooLong = a.errorMessage().map(m -> CONTEXT_OVERFLOW.matcher(m).find()).orElse(false);
        return tooLong ? TerminationReasonEnum.PROMPT_TOO_LONG : TerminationReasonEnum.PROVIDER_ERROR;
    }

    /**
     * Runs the context pipeline (transformContext hooks, then the stages), prepares the request and streams one reply;
     * empty when transformContext failed (an Error event was emitted); {@link #SKIPPED} when an overflow retry would
     * resend an unchanged context.
     */
    private Optional<AssistantMessage> requestReply(Run r, int turn, CancelToken cancel, boolean overflow) {
        List<Message> before = List.copyOf(r.context);
        try {
            r.context = new ArrayList<>(hooks.transformContext(before, r.state(turn, overflow, cancel)));
        } catch (RuntimeException e) {
            r.sink.accept(new AgentEvent.Error("transformContext hook failed: " + e.getMessage(), Optional.of(e)));
            return Optional.empty();
        }
        boolean compacted = changed(before, r.context);
        if (compacted) {
            r.sink.accept(new AgentEvent.ContextCompacted(before.size(), r.context.size()));
        }
        List<Message> view = r.context;
        for (ContextStage stage : config.stages()) {
            List<Message> in = view;
            try {
                view = stage.apply(in, r.state(turn, overflow, cancel));
            } catch (RuntimeException e) {
                log.warn("context stage {} failed; skipping it", stage.name(), e);
            }
            if (view != in) {
                boolean summary = stage.kind() == AgentEvent.ContextCompacted.KindEnum.SUMMARY;
                r.context = summary ? new ArrayList<>(view) : r.context;
                compacted |= summary;
                r.sink.accept(
                    new AgentEvent.ContextCompacted(in.size(), view.size(), stage.kind(), stage.summaryOf(view)));
            }
        }
        if (!compacted && overflow) {
            log.warn("context overflow: compaction freed nothing, not retrying");
            return Optional.of(SKIPPED);
        }
        boolean toolsOn = config.model().capabilities().contains(ModelRef.CapabilityEnum.TOOLS);
        List<ToolDefinition> offered = toolsOn ? tools.definitions() : List.of();
        ChatRequest request =
            new ChatRequest(config.model(), Optional.of(config.systemPrompt()), view, offered, config.options());
        try {
            request = hooks.prepareRequest(request, r.state(turn, overflow, cancel));
        } catch (RuntimeException e) {
            log.warn("prepareRequest hook failed; sending the unmodified request", e);
        }
        ChatRequest sent = toolsOn ? withToolsRegisteredSince(request, offered) : request;
        config.stages().forEach(s -> s.requestSent(sent));
        r.sink.accept(new AgentEvent.TurnStart(turn,
            ESTIMATOR.estimate(sent.messages(), sent.tools(), sent.systemPrompt().orElse(""))));
        r.sink.accept(new AgentEvent.MessageStart(shell()));
        AssistantMessage reply = provider.stream(sent, ev -> r.sink.accept(new AgentEvent.MessageUpdate(ev)), cancel);
        r.usage = r.usage.plus(reply.usage());
        r.context.add(reply);
        r.added.add(reply);
        r.sink.accept(new AgentEvent.MessageEnd(reply));
        return Optional.of(reply);
    }

    /**
     * Adds the tools registered while the hooks ran (for example by a dynamic tool source a hook waited for) to what
     * the hooks returned. Tools a hook removed on purpose stay removed: only names absent before the hooks are added.
     */
    private ChatRequest withToolsRegisteredSince(ChatRequest request, List<ToolDefinition> before) {
        Set<String> known = new HashSet<>();
        before.forEach(d -> known.add(d.name()));
        request.tools().forEach(d -> known.add(d.name()));
        List<ToolDefinition> added = tools.definitions().stream().filter(d -> !known.contains(d.name())).toList();
        if (added.isEmpty()) {
            return request;
        }
        List<ToolDefinition> merged = new ArrayList<>(request.tools());
        merged.addAll(added);
        return new ChatRequest(request.model(), request.systemPrompt(), request.messages(), merged, request.options());
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

    /** Mutable state of one run, confined to the calling thread. */
    private final class Run {
        private final List<Message> added = new ArrayList<>();
        private final Consumer<AgentEvent> sink;
        private List<Message> context;
        private Usage usage = Usage.ZERO;
        private boolean overflowRetried;
        private int lengthRetries;

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
