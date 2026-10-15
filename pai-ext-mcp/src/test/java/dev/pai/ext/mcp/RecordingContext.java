/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.ext.SlashCommand;
import dev.pai.core.ext.UserInteraction;
import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.tool.Tool;
import dev.pai.model.Provider;

/**
 * An {@link ExtensionContext} that records registrations and rejects duplicate tool names like the real registry.
 */
class RecordingContext implements ExtensionContext {

    final Map<String, Tool> tools = new ConcurrentHashMap<>();
    final List<String> log = new CopyOnWriteArrayList<>();
    final List<AgentHooks> hooks = new CopyOnWriteArrayList<>();
    final List<String> notices = new CopyOnWriteArrayList<>();
    private final Path home;
    private final Path cwd;
    private final boolean trusted;

    RecordingContext(Path home, Path cwd) {
        this(home, cwd, true);
    }

    RecordingContext(Path home, Path cwd, boolean trusted) {
        this.home = home;
        this.cwd = cwd;
        this.trusted = trusted;
    }

    @Override
    public void registerTool(Tool tool) {
        String name = tool.definition().name();
        if (tools.putIfAbsent(name, tool) != null) {
            throw new IllegalStateException("tool already registered: " + name);
        }
        log.add("+" + name);
    }

    @Override
    public void unregisterTool(String name) {
        tools.remove(name);
        log.add("-" + name);
    }

    @Override
    public void registerCommand(SlashCommand cmd) {
        log.add("/" + cmd.name());
    }

    @Override
    public void registerProvider(Provider p) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addHooks(AgentHooks h) {
        hooks.add(h);
    }

    @Override
    public void on(Class<? extends AgentEvent> type, Consumer<AgentEvent> l) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onSession(Class<? extends SessionEvent> type, Consumer<SessionEvent> l) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Path configDir() {
        return home.resolve("ext").resolve(McpExtension.ID);
    }

    @Override
    public Path cwd() {
        return cwd;
    }

    @Override
    public Path paiHome() {
        return home;
    }

    @Override
    public void notify(String message) {
        notices.add(message);
    }

    @Override
    public Optional<UserInteraction> ui() {
        return Optional.empty();
    }

    @Override
    public boolean projectTrusted() {
        return trusted;
    }

    @Override
    public Optional<Tool> tool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Optional<String> option(String key) {
        return Optional.empty();
    }

    @Override
    public void appendEntry(String customType, JsonNode data) {
        log.add("entry " + customType);
    }

    @Override
    public Optional<SessionEntry.CustomEntry> latestEntry(String customType) {
        return Optional.empty();
    }

    @Override
    public Logger logger() {
        return LoggerFactory.getLogger("test.mcp");
    }
}
