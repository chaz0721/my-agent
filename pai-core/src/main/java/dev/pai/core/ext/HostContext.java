/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.loop.AgentConfig;
import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.AgentLoop;
import dev.pai.core.loop.LoopState;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;
import dev.pai.model.Provider;

/**
 * The context {@link ExtensionHost} hands to one extension; it remembers what the extension registered so that a failed
 * load can be withdrawn. Every method may be called from any thread.
 */
final class HostContext implements ExtensionContext {

    private static final Logger log = LoggerFactory.getLogger(HostContext.class);

    private final ExtensionHost host;
    private final String id;
    private final List<String> toolNames = new CopyOnWriteArrayList<>();
    private final List<String> commandNames = new CopyOnWriteArrayList<>();
    private final List<String> providerIds = new CopyOnWriteArrayList<>();
    private final List<AgentHooks> ownHooks = new CopyOnWriteArrayList<>();

    HostContext(ExtensionHost host, String id) {
        this.host = host;
        this.id = id;
    }

    @Override
    public void registerTool(Tool tool) {
        host.tools().register(tool, id);
        toolNames.add(tool.definition().name());
    }

    @Override
    public void unregisterTool(String name) {
        host.tools().unregister(name);
        toolNames.remove(name);
    }

    @Override
    public void registerCommand(SlashCommand cmd) {
        host.registerCommand(cmd);
        commandNames.add(cmd.name());
    }

    @Override
    public void registerProvider(Provider p) {
        host.providers().register(p);
        providerIds.add(p.id());
    }

    @Override
    public void addHooks(AgentHooks hooks) {
        host.addHooks(hooks);
        ownHooks.add(hooks);
    }

    @Override
    public void on(Class<? extends AgentEvent> type, Consumer<AgentEvent> l) {
        host.addAgentListener(new ExtensionHost.Listener<>(id, type, l));
    }

    @Override
    public void onSession(Class<? extends SessionEvent> type, Consumer<SessionEvent> l) {
        host.addSessionListener(new ExtensionHost.Listener<>(id, type, l));
    }

    @Override
    public Path configDir() {
        return host.settings().paiHome().resolve("ext").resolve(id);
    }

    @Override
    public Path cwd() {
        return host.settings().cwd();
    }

    @Override
    public Path paiHome() {
        return host.settings().paiHome();
    }

    @Override
    public void notify(String message) {
        try {
            host.settings().notifier().accept(message);
        } catch (RuntimeException ex) {
            log.warn("notice of {} could not be shown", id, ex);
        }
    }

    @Override
    public Optional<UserInteraction> ui() {
        return host.settings().ui();
    }

    @Override
    public boolean projectTrusted() {
        return host.settings().projectTrusted();
    }

    @Override
    public Optional<Tool> tool(String name) {
        return host.tools().get(name);
    }

    @Override
    public Optional<String> option(String key) {
        return Optional.ofNullable(host.settings().options().get(key));
    }

    @Override
    public void appendEntry(String customType, JsonNode data) {
        if (host.settings().session().append(customType, data).isEmpty()) {
            log.debug("extension {} wrote a {} entry without an active session; dropped", id, customType);
        }
    }

    @Override
    public Optional<SessionEntry.CustomEntry> latestEntry(String customType) {
        return host.settings().session().latest(customType);
    }

    @Override
    public AgentLoop subLoop(AgentConfig config, Predicate<Tool> offered) {
        Provider provider = host.settings().provider().get()
            .orElseThrow(() -> new IllegalStateException("no provider for a sub-agent: the host has no current model"));
        ToolRegistry tools = new ToolRegistry();
        for (Tool t : host.tools().all()) {
            if (offered.test(t)) {
                tools.register(t, host.tools().sourceOf(t.definition().name()).orElse(id));
            }
        }
        AgentHooks chain = host.hooks();
        AgentHooks toolHooks = new AgentHooks() {
            @Override
            public ToolDecision beforeToolCall(ContentBlock.ToolCall call, Tool tool, LoopState state) {
                return chain.beforeToolCall(call, tool, state);
            }

            @Override
            public ToolResult afterToolCall(ContentBlock.ToolCall call, ToolResult result, LoopState state) {
                return chain.afterToolCall(call, result, state);
            }
        };
        return new AgentLoop(provider, tools, toolHooks, config, host.settings().cwd());
    }

    @Override
    public Logger logger() {
        return LoggerFactory.getLogger("pai.ext." + id);
    }

    /** Removes everything this extension registered. */
    void withdraw() {
        toolNames.forEach(host.tools()::unregister);
        providerIds.forEach(host.providers()::unregister);
        host.removeHooks(ownHooks);
        host.removeOwned(id, commandNames);
    }
}
