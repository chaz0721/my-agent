/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.pai.model.ToolDefinition;

/**
 * Tools by name, each tagged with the id of the extension that registered it. Holds no execution logic.
 */
public final class ToolRegistry {

    private final Map<String, Entry> tools = new LinkedHashMap<>();

    /**
     * Registers a tool.
     *
     * @param t the tool
     * @param sourceId id of the registering extension
     * @throws IllegalStateException when a tool with the same name exists
     */
    public synchronized void register(Tool t, String sourceId) {
        String name = t.definition().name();
        if (tools.containsKey(name)) {
            throw new IllegalStateException(
                "tool already registered: " + name + " (by " + tools.get(name).sourceId() + ")");
        }
        tools.put(name, new Entry(t, sourceId));
    }

    /**
     * Removes a tool if present.
     *
     * @param name tool name
     * @return whether a tool was removed
     */
    public synchronized boolean unregister(String name) {
        return tools.remove(name) != null;
    }

    /**
     * Looks a tool up by name.
     *
     * @param name tool name
     * @return the tool, if registered
     */
    public synchronized Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name)).map(Entry::tool);
    }

    /**
     * Returns the id of the extension that registered a tool.
     *
     * @param name tool name
     * @return the source id, if registered
     */
    public synchronized Optional<String> sourceOf(String name) {
        return Optional.ofNullable(tools.get(name)).map(Entry::sourceId);
    }

    /**
     * Returns all tools in registration order.
     *
     * @return the tools
     */
    public synchronized List<Tool> all() {
        return tools.values().stream().map(Entry::tool).toList();
    }

    /**
     * Returns the definitions of all tools in registration order.
     *
     * @return the definitions
     */
    public synchronized List<ToolDefinition> definitions() {
        return tools.values().stream().map(e -> e.tool().definition()).toList();
    }

    private record Entry(Tool tool, String sourceId) {
    }
}
