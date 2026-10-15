/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.session.SessionEntry;
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
     * Returns the working directory pai was started in, for project-level configuration such as {@code <cwd>/.pai/}.
     *
     * @return the absolute cwd
     */
    Path cwd();

    /**
     * Returns the pai home directory ({@code ~/.pai} unless {@code PAI_HOME} says otherwise). Its layout is part of the
     * contract: {@code sessions/}, {@code extensions/}, {@code ext/<id>/}, {@code skills/}, {@code models.json},
     * {@code mcp.json}, {@code .env}.
     *
     * @return the absolute pai home
     */
    Path paiHome();

    /**
     * Shows the user a one-line notice; the terminal UI renders it, plain output goes to the same stream as other
     * output. Safe to call from any thread.
     *
     * @param message the notice, one line
     */
    void notify(String message);

    /**
     * Returns the way to ask the user questions, present only when pai runs interactively.
     *
     * @return the interaction, or empty when nobody can answer (an extension must then fall back to a safe default)
     */
    Optional<UserInteraction> ui();

    /**
     * Returns whether the user trusts the working directory; when false, project-level configuration that runs code or
     * redirects credentials ({@code <cwd>/.env}, {@code <cwd>/.pai/mcp.json}, {@code <cwd>/.pai/extensions}) must be
     * ignored.
     *
     * @return whether the project is trusted
     */
    boolean projectTrusted();

    /**
     * Looks up a registered tool by name, to run it directly. A tool run this way bypasses the hooks chain (no
     * approval, no guards), so only commands the model did not write may be passed to it.
     *
     * @param name tool name
     * @return the tool, if registered
     */
    Optional<Tool> tool(String name);

    /**
     * Returns a run-time option the host was started with (command line or environment), such as {@code approval}. Keys
     * are lower case with hyphens; the raw value is parsed by the extension. Never carries credentials.
     *
     * @param key the option name
     * @return the value, empty when not given
     */
    Optional<String> option(String key);

    /**
     * Appends extension data to the active session after the current leaf, which then moves to it; ignored when there
     * is no session. Context building skips such entries.
     *
     * @param customType extension-defined type, by convention the extension's own name for the data
     * @param data payload
     */
    void appendEntry(String customType, JsonNode data);

    /**
     * Finds the latest entry of a type on the current branch (the parent chain of the leaf), so a fork sees only what
     * was written on its own branch, and compaction does not hide it.
     *
     * @param customType extension-defined type
     * @return the entry, empty when the branch has none or there is no session
     */
    Optional<SessionEntry.CustomEntry> latestEntry(String customType);

    /**
     * Returns a logger named after this extension.
     *
     * @return the logger
     */
    Logger logger();
}
