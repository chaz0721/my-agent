/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;

/**
 * What kind of sub-agent to run: its prompt, the tools it may use and whether it sees the project context.
 *
 * @param name the type name the model passes as {@code agentType}
 * @param description one line shown in the {@code spawn_agent} description
 * @param systemPromptAddendum instructions appended to the sub-agent's system prompt
 * @param allowedTools tool names the sub-agent gets; empty means every tool of the parent, {@value #READ_ONLY} means
 *            only calls whose effect for their input is read-only
 * @param projectContext whether {@code PAI.md} goes into the sub-agent's system prompt
 * @param model another model id of the parent's provider, empty for the parent's model
 */
public record AgentType(String name, String description, String systemPromptAddendum, Set<String> allowedTools,
    boolean projectContext, Optional<String> model) {

    /** The {@code tools} value that restricts a type to read-only calls. */
    public static final String READ_ONLY = "read-only";

    /**
     * Tools a sub-agent never gets: this extension's own (sub-agents do not nest) and the task list's, which belongs to
     * the parent session (its entries would land there).
     */
    static final Set<String> NEVER_OFFERED =
        Set.of(SubagentTools.SPAWN, SubagentTools.RESULT, SubagentTools.CANCEL, "todo_write", "todo_read");

    /** Validates fields and copies the tool names. */
    public AgentType {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(systemPromptAddendum, "systemPromptAddendum");
        allowedTools = Set.copyOf(allowedTools);
        Objects.requireNonNull(model, "model");
    }

    /**
     * Returns whether this type may only make read-only calls; others are blocked before they run.
     *
     * @return true for {@value #READ_ONLY} types such as explore and reviewer
     */
    public boolean readOnly() {
        return allowedTools.contains(READ_ONLY);
    }

    /**
     * Returns whether a registered tool is offered to this type: never this extension's own tools or the task list's,
     * for a read-only type no tool declared to write files (calls of the others are checked one by one), and only the
     * listed names when {@link #allowedTools()} names any.
     *
     * @param tool the tool
     * @return whether the sub-agent sees it
     */
    public boolean offers(Tool tool) {
        String n = tool.definition().name();
        if (NEVER_OFFERED.contains(n)) {
            return false;
        }
        if (readOnly() && tool.effect() == ToolEffectEnum.WRITE_FS) {
            return false;
        }
        boolean anyName = allowedTools.stream().allMatch(READ_ONLY::equals);
        return anyName || allowedTools.contains(n);
    }
}
