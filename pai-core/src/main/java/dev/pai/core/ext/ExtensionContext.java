/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.nio.file.Path;
import java.util.function.Consumer;

import org.slf4j.Logger;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.tool.Tool;
import dev.pai.model.Provider;

/**
 * The closed registration surface offered to an extension. It never hands out loop or registry internals.
 */
public interface ExtensionContext {

    /**
     * Registers a tool under this extension's id.
     *
     * @param tool the tool
     */
    void registerTool(Tool tool);

    /**
     * Removes a tool, for dynamic tool sources.
     *
     * @param name tool name
     */
    void unregisterTool(String name);

    /**
     * Registers a slash command.
     *
     * @param cmd the command
     */
    void registerCommand(SlashCommand cmd);

    /**
     * Registers a provider.
     *
     * @param p the provider
     */
    void registerProvider(Provider p);

    /**
     * Adds hooks; hooks of all extensions are chained in registration order.
     *
     * @param hooks the hooks
     */
    void addHooks(AgentHooks hooks);

    /**
     * Subscribes to agent events of a type.
     *
     * @param type event type
     * @param l listener, called on the loop thread
     */
    void on(Class<? extends AgentEvent> type, Consumer<AgentEvent> l);

    /**
     * Subscribes to session lifecycle events of a type.
     *
     * @param type event type
     * @param l listener
     */
    void onSession(Class<? extends SessionEvent> type, Consumer<SessionEvent> l);

    /**
     * Returns this extension's configuration directory ({@code ~/.pai/ext/<id>/}); it may not exist yet.
     *
     * @return the directory
     */
    Path configDir();

    /**
     * Returns a logger named after this extension.
     *
     * @return the logger
     */
    Logger logger();
}
