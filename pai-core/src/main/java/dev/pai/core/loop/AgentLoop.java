/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import dev.pai.core.internal.LoopSnapshot;
import dev.pai.core.internal.ToolRunner;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatRequest;
import dev.pai.model.Message;
import dev.pai.model.Provider;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

/**
 * The agent loop: stream a reply, execute its tool calls, feed results back, repeat until the model stops calling
 * tools. Pure control flow; everything observable is an {@link AgentEvent} delivered on the calling thread.
 */
public final class AgentLoop {

    private final Provider provider;
    private final ToolRegistry tools;
    private final AgentHooks hooks;
    private final AgentConfig config;
    private final Path cwd;
    private final Clock clock;

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
        this.provider = provider;
        this.tools = tools;
        this.hooks = hooks;
        this.config = config;
        this.cwd = cwd;
        this.clock = clock;
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
        r.context = new ArrayList<>(hooks.transformContext(List.copyOf(r.context), r.state(turn)));
        ChatRequest request = new ChatRequest(config.model(), Optional.of(config.systemPrompt()), r.context,
            tools.definitions(), config.options());
        r.sink.accept(new AgentEvent.MessageStart(shell()));
        AssistantMessage reply =
            provider.stream(request, ev -> r.sink.accept(new AgentEvent.MessageUpdate(ev)), cancel);
        r.usage = r.usage.plus(reply.usage());
        r.context.add(reply);
        r.added.add(reply);
        r.sink.accept(new AgentEvent.MessageEnd(reply));
        if (reply.stopReason() == StopReasonEnum.ERROR || reply.stopReason() == StopReasonEnum.ABORTED) {
            r.sink.accept(new AgentEvent.TurnEnd(reply, List.of()));
            String msg = reply.errorMessage().orElse(reply.stopReason().name().toLowerCase(Locale.ROOT));
            r.sink.accept(new AgentEvent.Error(msg, Optional.empty()));
            return false;
        }
        List<ToolResultMessage> results =
            reply.toolCalls().isEmpty() ? List.of() : runner.run(reply.toolCalls(), r.state(turn), r.sink, cancel);
        results.forEach(r::append);
        r.sink.accept(new AgentEvent.TurnEnd(reply, results));
        List<Message> steering = hooks.steeringMessages();
        steering.forEach(r::append);
        return !results.isEmpty() || !steering.isEmpty();
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

        private LoopState state(int turn) {
            return new LoopSnapshot(context, config.model(), usage, turn, cwd);
        }
    }
}
