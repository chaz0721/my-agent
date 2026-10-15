/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.Provider;
import dev.pai.model.ProviderRegistry;

/**
 * Everything installed extensions registered: tools, the hooks chain, commands, providers and listeners. The CLI
 * assembles its loop from this object.
 */
public final class ExtensionHost {

    private static final Logger log = LoggerFactory.getLogger(ExtensionHost.class);

    private final Settings settings;
    private final ToolRegistry tools = new ToolRegistry();
    private final ProviderRegistry providers = new ProviderRegistry();
    private final List<AgentHooks> hookList = new CopyOnWriteArrayList<>();
    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();
    private final List<Listener<AgentEvent>> agentListeners = new CopyOnWriteArrayList<>();
    private final List<Listener<SessionEvent>> sessionListeners = new CopyOnWriteArrayList<>();
    private final List<Extension> loaded = new CopyOnWriteArrayList<>();
    private final List<ExtensionStatus> statuses = new CopyOnWriteArrayList<>();
    private final AgentHooks liveHooks = new LiveHooks();

    ExtensionHost(Settings settings) {
        this.settings = settings;
    }

    /**
     * What the host tells extensions about the environment they run in.
     *
     * @param paiHome the pai home directory
     * @param cwd the working directory
     * @param projectTrusted whether the user trusts the working directory
     * @param ui how to ask the user, empty when pai runs non-interactively
     * @param notifier shows a one-line notice to the user; must be safe to call from any thread
     * @param options run-time options by lower-case key (see {@link ExtensionContext#option(String)})
     * @param session the active session, for entries extensions write
     */
    public record Settings(Path paiHome, Path cwd, boolean projectTrusted, Optional<UserInteraction> ui,
        Consumer<String> notifier, Map<String, String> options, SessionAccess session) {

        /**
         * Validates fields and copies the options.
         */
        public Settings {
            Objects.requireNonNull(paiHome, "paiHome");
            Objects.requireNonNull(cwd, "cwd");
            Objects.requireNonNull(ui, "ui");
            Objects.requireNonNull(notifier, "notifier");
            options = Map.copyOf(options);
            Objects.requireNonNull(session, "session");
        }

        /**
         * Settings without options and without a session.
         *
         * @param paiHome the pai home directory
         * @param cwd the working directory
         * @param projectTrusted whether the user trusts the working directory
         * @param ui how to ask the user, empty when pai runs non-interactively
         * @param notifier shows a one-line notice to the user; must be safe to call from any thread
         */
        public Settings(Path paiHome, Path cwd, boolean projectTrusted, Optional<UserInteraction> ui,
            Consumer<String> notifier) {
            this(paiHome, cwd, projectTrusted, ui, notifier, Map.of(), SessionAccess.none());
        }

        /**
         * Settings for a trusted directory without a user to ask; notices go to the log.
         *
         * @param paiHome the pai home directory
         * @param cwd the working directory
         * @return the settings
         */
        public static Settings of(Path paiHome, Path cwd) {
            return new Settings(paiHome, cwd, true, Optional.empty(), m -> log.info("notice: {}", m));
        }

        /**
         * Returns a copy with other options.
         *
         * @param opts options by lower-case key
         * @return the copy
         */
        public Settings withOptions(Map<String, String> opts) {
            return new Settings(paiHome, cwd, projectTrusted, ui, notifier, opts, session);
        }

        /**
         * Returns a copy with another session access.
         *
         * @param access the access
         * @return the copy
         */
        public Settings withSession(SessionAccess access) {
            return new Settings(paiHome, cwd, projectTrusted, ui, notifier, options, access);
        }
    }

    /**
     * Loads one extension; if its onLoad throws (or a {@link LinkageError} shows an extension built against another
     * core), everything it registered is withdrawn and it stays disabled. A second extension with an id already loaded
     * is rejected.
     */
    void load(Extension e, String source) {
        String id = e.id();
        if (loaded.stream().anyMatch(x -> x.id().equals(id))) {
            log.warn("extension {} from {} has the id of a loaded extension and is skipped", id, source);
            statuses.add(ExtensionStatus.disabled(id, source, "duplicate id"));
            return;
        }
        Context ctx = new Context(id);
        try {
            e.onLoad(ctx);
            loaded.add(e);
            statuses.add(ExtensionStatus.enabled(id, source));
        } catch (RuntimeException | LinkageError ex) {
            log.warn("extension {} failed to load and is disabled", id, ex);
            ctx.withdraw();
            statuses.add(ExtensionStatus.disabled(id, source, "onLoad failed: " + ex));
        }
    }

    /** Records an extension that was rejected before it could be loaded. */
    void reject(ExtensionStatus status) {
        statuses.add(status);
    }

    /**
     * Returns every extension seen: loaded ones and those rejected or disabled, with the reason.
     *
     * @return statuses in load order
     */
    public List<ExtensionStatus> statuses() {
        return List.copyOf(statuses);
    }

    /**
     * Returns the tool registry.
     *
     * @return tools of all extensions
     */
    public ToolRegistry tools() {
        return tools;
    }

    /**
     * Returns the provider registry.
     *
     * @return providers registered by extensions
     */
    public ProviderRegistry providers() {
        return providers;
    }

    /**
     * Returns hooks that always delegate to the current chain of every registered hook.
     *
     * @return the hooks
     */
    public AgentHooks hooks() {
        return liveHooks;
    }

    /**
     * Looks a slash command up by name.
     *
     * @param name name without slash
     * @return the command, if registered
     */
    public synchronized Optional<SlashCommand> command(String name) {
        return Optional.ofNullable(commands.get(name));
    }

    /**
     * Returns all commands in registration order.
     *
     * @return commands
     */
    public synchronized List<SlashCommand> commands() {
        return List.copyOf(commands.values());
    }

    /**
     * Registers a command owned by the host application itself.
     *
     * @param cmd the command
     * @throws IllegalStateException when the name is taken
     */
    public synchronized void registerCommand(SlashCommand cmd) {
        if (commands.putIfAbsent(cmd.name(), cmd) != null) {
            throw new IllegalStateException("command already registered: " + cmd.name());
        }
    }

    /**
     * Delivers an agent event to matching listeners; listener failures are logged, never propagated.
     *
     * @param event the event
     */
    public void dispatch(AgentEvent event) {
        deliver(agentListeners, event);
    }

    /**
     * Delivers a session event to matching listeners.
     *
     * @param event the event
     */
    public void dispatch(SessionEvent event) {
        deliver(sessionListeners, event);
    }

    /**
     * Calls {@link Extension#onUnload()} on every extension in reverse order.
     */
    public void unloadAll() {
        for (int i = loaded.size() - 1; i >= 0; i--) {
            try {
                loaded.get(i).onUnload();
            } catch (RuntimeException ex) {
                log.warn("extension {} failed to unload", loaded.get(i).id(), ex);
            }
        }
        loaded.clear();
    }

    private static <T> void deliver(List<Listener<T>> listeners, T event) {
        for (Listener<T> l : listeners) {
            if (l.type().isInstance(event)) {
                try {
                    l.consumer().accept(event);
                } catch (RuntimeException ex) {
                    log.warn("listener of {} failed on {}", l.ownerId(), event.getClass().getSimpleName(), ex);
                }
            }
        }
    }

    private record Listener<T>(String ownerId, Class<? extends T> type, Consumer<T> consumer) {
    }

    /**
     * Hooks view that rebuilds the chain on every call so that late registrations take effect.
     */
    private final class LiveHooks implements AgentHooks {
        private AgentHooks chain() {
            return AgentHooks.chain(hookList);
        }

        @Override
        public ToolDecision beforeToolCall(ContentBlock.ToolCall call, Tool tool, LoopState state) {
            return chain().beforeToolCall(call, tool, state);
        }

        @Override
        public ToolResult afterToolCall(ContentBlock.ToolCall call, ToolResult result, LoopState state) {
            return chain().afterToolCall(call, result, state);
        }

        @Override
        public List<Message> transformContext(List<Message> messages, LoopState state) {
            return chain().transformContext(messages, state);
        }

        @Override
        public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
            return chain().prepareRequest(request, state);
        }

        @Override
        public List<Message> steeringMessages() {
            return chain().steeringMessages();
        }
    }

    /**
     * The context handed to one extension. Every method may be called from any thread.
     */
    private final class Context implements ExtensionContext {
        private final String id;
        private final List<String> toolNames = new CopyOnWriteArrayList<>();
        private final List<String> commandNames = new CopyOnWriteArrayList<>();
        private final List<String> providerIds = new CopyOnWriteArrayList<>();
        private final List<AgentHooks> ownHooks = new CopyOnWriteArrayList<>();

        private Context(String id) {
            this.id = id;
        }

        @Override
        public void registerTool(Tool tool) {
            tools.register(tool, id);
            toolNames.add(tool.definition().name());
        }

        @Override
        public void unregisterTool(String name) {
            tools.unregister(name);
            toolNames.remove(name);
        }

        @Override
        public void registerCommand(SlashCommand cmd) {
            ExtensionHost.this.registerCommand(cmd);
            commandNames.add(cmd.name());
        }

        @Override
        public void registerProvider(Provider p) {
            providers.register(p);
            providerIds.add(p.id());
        }

        @Override
        public void addHooks(AgentHooks hooks) {
            hookList.add(hooks);
            ownHooks.add(hooks);
        }

        @Override
        public void on(Class<? extends AgentEvent> type, Consumer<AgentEvent> l) {
            agentListeners.add(new Listener<>(id, type, l));
        }

        @Override
        public void onSession(Class<? extends SessionEvent> type, Consumer<SessionEvent> l) {
            sessionListeners.add(new Listener<>(id, type, l));
        }

        @Override
        public Path configDir() {
            return settings.paiHome().resolve("ext").resolve(id);
        }

        @Override
        public Path cwd() {
            return settings.cwd();
        }

        @Override
        public Path paiHome() {
            return settings.paiHome();
        }

        @Override
        public void notify(String message) {
            try {
                settings.notifier().accept(message);
            } catch (RuntimeException ex) {
                log.warn("notice of {} could not be shown", id, ex);
            }
        }

        @Override
        public Optional<UserInteraction> ui() {
            return settings.ui();
        }

        @Override
        public boolean projectTrusted() {
            return settings.projectTrusted();
        }

        @Override
        public Optional<Tool> tool(String name) {
            return tools.get(name);
        }

        @Override
        public Optional<String> option(String key) {
            return Optional.ofNullable(settings.options().get(key));
        }

        @Override
        public void appendEntry(String customType, JsonNode data) {
            if (settings.session().append(customType, data).isEmpty()) {
                log.debug("extension {} wrote a {} entry without an active session; dropped", id, customType);
            }
        }

        @Override
        public Optional<SessionEntry.CustomEntry> latestEntry(String customType) {
            return settings.session().latest(customType);
        }

        @Override
        public Logger logger() {
            return LoggerFactory.getLogger("pai.ext." + id);
        }

        /** Removes everything this extension registered. */
        private void withdraw() {
            toolNames.forEach(tools::unregister);
            providerIds.forEach(providers::unregister);
            hookList.removeAll(ownHooks);
            synchronized (ExtensionHost.this) {
                commandNames.forEach(commands::remove);
            }
            agentListeners.removeIf(l -> l.ownerId().equals(id));
            sessionListeners.removeIf(l -> l.ownerId().equals(id));
        }
    }
}
