/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

import dev.pai.core.compaction.CharsTokenEstimatorImpl;
import dev.pai.core.compaction.CompactingHooks;
import dev.pai.core.ext.CommandContext;
import dev.pai.core.ext.CommandResult;
import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.ext.SlashCommand;
import dev.pai.core.loop.AgentConfig;
import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.AgentLoop;
import dev.pai.core.loop.LoopState;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;
import dev.pai.tui.Renderer;
import dev.pai.tui.StatusLine;

/**
 * The interactive loop: reads a line, runs slash commands locally, sends everything else to the agent loop and records
 * every new message in the session. Holds the session state the commands work on: current model, leaf and context.
 */
final class Repl {

    private static final int PERCENT = 100;
    private static final int SESSION_SHORT = 8;

    private final Setup setup;
    private final Renderer renderer;
    private final AtomicReference<CancelTokenSource> running = new AtomicReference<>();
    private final Deque<String> pending = new ArrayDeque<>();
    private ProviderSetup.Selected current;
    private Optional<String> leaf;
    private List<Message> history;
    private Usage sessionUsage = Usage.ZERO;

    /**
     * What the REPL works with.
     *
     * @param host installed extensions
     * @param store session storage
     * @param session the active session, shared with the compaction hooks
     * @param config loop configuration of the startup model
     * @param compacting the compaction hooks
     * @param catalog configured models
     * @param env configuration layers, for keys of catalog models
     * @param debugHttp whether request bodies are logged
     * @param cwd working directory
     * @param paiHome the {@code ~/.pai} directory
     */
    record Setup(ExtensionHost host, SessionStore store, AtomicReference<SessionId> session, AgentConfig config,
        CompactingHooks compacting, ModelCatalog catalog, EnvConfig env, boolean debugHttp, Path cwd, Path paiHome) {
    }

    Repl(Setup setup, ProviderSetup.Selected selected, Renderer renderer) {
        this.setup = setup;
        this.renderer = renderer;
        this.current = selected;
        this.leaf = setup.store().leafId(session());
        this.history = context();
    }

    /** Cancels the running request; returns false when nothing was running (the caller should exit). */
    boolean interrupt() {
        CancelTokenSource c = running.get();
        if (c == null || c.isCancelled()) {
            return false;
        }
        c.cancel();
        return true;
    }

    void run() throws IOException {
        refreshStatus();
        while (true) {
            String line;
            try {
                line = renderer.readLine("> ");
            } catch (UserInterruptException e) {
                renderer.println("(Ctrl+D or /quit to exit)");
                continue;
            } catch (EndOfFileException e) {
                return;
            }
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith("/") && command(text)) {
                return;
            }
            if (!text.startsWith("/")) {
                prompt(text);
            }
            while (!pending.isEmpty()) {
                prompt(pending.removeFirst());
            }
        }
    }

    private boolean command(String text) {
        int space = text.indexOf(' ');
        String name = space < 0 ? text.substring(1) : text.substring(1, space);
        String args = space < 0 ? "" : text.substring(space + 1).strip();
        Optional<SlashCommand> cmd = setup.host().command(name);
        if (cmd.isEmpty()) {
            renderer.println("unknown command: /" + name + " (see /help)");
            return false;
        }
        CommandResult r;
        try {
            r = cmd.get().run(args, new Context());
        } catch (IllegalArgumentException | IllegalStateException e) {
            renderer.println("/" + name + ": " + e.getMessage());
            return false;
        }
        if (!r.output().isEmpty()) {
            renderer.println(r.output());
        }
        return r.quit();
    }

    private void prompt(String text) {
        CancelTokenSource cancel = new CancelTokenSource();
        running.set(cancel);
        try {
            AgentHooks hooks = AgentHooks.chain(List.of(setup.host().hooks(), setup.compacting()));
            AgentLoop loop = new AgentLoop(current.provider(), setup.host().tools(), hooks,
                setup.config().withModel(current.model()), setup.cwd());
            loop.run(List.copyOf(history), UserMessage.text(text), this::onEvent, cancel);
        } finally {
            running.set(null);
        }
        history = context();
        refreshStatus();
    }

    private void onEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.MessageEnd end -> {
                leaf = Optional.of(setup.store().append(session(),
                    new SessionEntry.MessageEntry("", leaf, Instant.now(), end.message())));
                if (end.message() instanceof AssistantMessage a) {
                    sessionUsage = sessionUsage.plus(a.usage());
                }
            }
            case AgentEvent.ContextCompacted c -> leaf = setup.store().leafId(session());
            default -> {
                // other events are only rendered
            }
        }
        renderer.accept(event);
        setup.host().dispatch(event);
    }

    private void refreshStatus() {
        ModelRef m = current.model();
        int tokens = new CharsTokenEstimatorImpl().estimate(history, setup.host().tools().definitions(),
            setup.config().systemPrompt());
        int pct = m.contextWindow() <= 0 ? 0 : (int)Math.min(PERCENT, (long)tokens * PERCENT / m.contextWindow());
        String sid = session().value();
        renderer.status(new StatusLine(m.providerId() + "/" + m.modelId(), pct, sessionUsage,
            sid.substring(0, Math.min(SESSION_SHORT, sid.length())), StatusLine.IDLE));
    }

    private List<Message> context() {
        return leaf.map(l -> setup.store().buildContext(session(), l)).orElse(List.of());
    }

    SessionId session() {
        return setup.session().get();
    }

    Setup setup() {
        return setup;
    }

    Renderer renderer() {
        return renderer;
    }

    ProviderSetup.Selected current() {
        return current;
    }

    Optional<String> leaf() {
        return leaf;
    }

    /** Switches the model from the next turn on and records the change. */
    void switchModel(ProviderSetup.Selected next) {
        current = next;
        append(new SessionEntry.ModelChangeEntry("", leaf, Instant.now(), next.model().providerId(),
            next.model().modelId()));
        refreshStatus();
    }

    /** Points the leaf at an existing entry; the next message continues from there. */
    void fork(String entryId) {
        leaf = Optional.of(entryId);
        history = context();
        refreshStatus();
    }

    /** Appends an entry after the leaf and makes it the leaf. */
    String append(SessionEntry entry) {
        String id = setup.store().append(session(), entry);
        leaf = Optional.of(id);
        return id;
    }

    /**
     * Result of {@link #compactNow()}.
     *
     * @param compacted whether a compaction entry was written
     * @param before messages before
     * @param after messages after
     */
    record Compaction(boolean compacted, int before, int after) {
    }

    /** Compacts the current context now; Ctrl+C cancels the summary request. */
    Compaction compactNow() {
        List<Message> before = List.copyOf(history);
        CancelTokenSource cancel = new CancelTokenSource();
        running.set(cancel);
        List<Message> after;
        try {
            after = setup.compacting().compact(before, new CommandLoopState(before, cancel), true);
        } finally {
            running.set(null);
        }
        boolean compacted = after != before;
        if (compacted) {
            leaf = setup.store().leafId(session());
            history = context();
        }
        refreshStatus();
        return new Compaction(compacted, before.size(), history.size());
    }

    /** Starts a new session in the same directory; the old one stays on disk. */
    void clear() {
        setup.host().dispatch(new SessionEvent.SessionEnded(session()));
        setup.session().set(setup.store().create(setup.cwd()));
        leaf = Optional.empty();
        history = new ArrayList<>();
        sessionUsage = Usage.ZERO;
        setup.host().dispatch(new SessionEvent.SessionStarted(session(), false));
        refreshStatus();
    }

    /**
     * Loop snapshot for work a command does outside a run.
     */
    private final class CommandLoopState implements LoopState {
        private final List<Message> messages;
        private final CancelTokenSource cancel;

        private CommandLoopState(List<Message> messages, CancelTokenSource cancel) {
            this.messages = messages;
            this.cancel = cancel;
        }

        @Override
        public List<Message> messages() {
            return messages;
        }

        @Override
        public ModelRef model() {
            return current.model();
        }

        @Override
        public Usage usageThisRun() {
            return Usage.ZERO;
        }

        @Override
        public int turnIndex() {
            return 0;
        }

        @Override
        public Path cwd() {
            return setup.cwd();
        }

        @Override
        public CancelToken cancel() {
            return cancel;
        }
    }

    /**
     * The command context handed to slash commands.
     */
    private final class Context implements CommandContext {
        @Override
        public Optional<SessionId> session() {
            return Optional.of(Repl.this.session());
        }

        @Override
        public ModelRef model() {
            return current.model();
        }

        @Override
        public void println(String s) {
            renderer.println(s);
        }

        @Override
        public void sendUserMessage(String text) {
            pending.addLast(text);
        }
    }
}
