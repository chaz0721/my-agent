/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.core.tool.ToolResult;
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

    private final Path paiHome;
    private final ToolRegistry tools = new ToolRegistry();
    private final ProviderRegistry providers = new ProviderRegistry();
    private final List<AgentHooks> hookList = new CopyOnWriteArrayList<>();
    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();
    private final List<Listener<AgentEvent>> agentListeners = new CopyOnWriteArrayList<>();
    private final List<Listener<SessionEvent>> sessionListeners = new CopyOnWriteArrayList<>();
    private final List<Extension> loaded = new ArrayList<>();
    private final AgentHooks liveHooks = new LiveHooks();

    ExtensionHost(Path paiHome) {
        this.paiHome = paiHome;
    }

    void load(Extension e) {
        e.onLoad(new Context(e.id()));
        loaded.add(e);
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
        public List<Message> steeringMessages() {
            return chain().steeringMessages();
        }
    }

    /**
     * The context handed to one extension.
     */
    private final class Context implements ExtensionContext {
        private final String id;

        private Context(String id) {
            this.id = id;
        }

        @Override
        public void registerTool(Tool tool) {
            tools.register(tool, id);
        }

        @Override
        public void unregisterTool(String name) {
            tools.unregister(name);
        }

        @Override
        public void registerCommand(SlashCommand cmd) {
            ExtensionHost.this.registerCommand(cmd);
        }

        @Override
        public void registerProvider(Provider p) {
            providers.register(p);
        }

        @Override
        public void addHooks(AgentHooks hooks) {
            hookList.add(hooks);
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
            return paiHome.resolve("ext").resolve(id);
        }

        @Override
        public Logger logger() {
            return LoggerFactory.getLogger("pai.ext." + id);
        }
    }
}
