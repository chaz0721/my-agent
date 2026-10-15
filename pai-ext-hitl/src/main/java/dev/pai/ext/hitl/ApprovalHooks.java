/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.model.ContentBlock;

/**
 * Decides before every tool call, with the effect the tool reports for the call ({@code Tool.effect(input)}). A command
 * guard hit, or a path outside the working directory for a tool that is not read-only, blocks and cannot be approved. A
 * read-only tool reading outside always asks; so does a read-only command of a tool that is not read-only when its text
 * may name a path outside ({@link PathGuard#commandMayLeaveCwd}). Otherwise the mode and the tools allowed for the
 * whole session decide, and finally the {@link Approver}. The loop may call this from several threads: the approver is
 * asked one call at a time, and "always" answers given meanwhile are honoured.
 */
final class ApprovalHooks implements AgentHooks {

    static final String POLICY_PREFIX = "policy: ";
    static final String DENIED = "user denied";
    static final String NO_ANSWER = "approval required but nobody answered (pai is not interactive or input ended)";
    static final String READ_OUTSIDE_NOTE =
        "note: this read-only command may read outside the working directory; \"always\" is not remembered\n";
    static final String PLAN_BLOCK = "plan mode: read-only; explore with read-only tools and reply with a plan "
        + "instead (the user leaves plan mode with /plan off or Shift+Tab)";

    private volatile ApprovalModeEnum mode;
    private final PathGuard paths;
    private final CommandGuard commands;
    private final Approver approver;
    private final CallPreview preview;
    private final Set<String> alwaysAllowed = ConcurrentHashMap.newKeySet();
    private final ReentrantLock asking = new ReentrantLock();

    /**
     * Wiring of the hooks.
     *
     * @param mode the approval mode
     * @param paths the path guard
     * @param commands the command guard
     * @param approver who is asked
     * @param preview renders calls for the approver
     */
    record Parts(ApprovalModeEnum mode, PathGuard paths, CommandGuard commands, Approver approver,
        CallPreview preview) {
    }

    ApprovalHooks(Parts p) {
        this.mode = p.mode();
        this.paths = p.paths();
        this.commands = p.commands();
        this.approver = p.approver();
        this.preview = p.preview();
    }

    @Override
    public ToolDecision beforeToolCall(ContentBlock.ToolCall call, Tool tool, LoopState state) {
        boolean declaredReadOnly = tool.effect() == ToolEffectEnum.READ_ONLY;
        boolean readOnly = Tool.effectOf(tool, call.arguments()) == ToolEffectEnum.READ_ONLY;
        Optional<String> outside = paths.check(call);
        Optional<String> policy = commands.check(call).map(id -> "command matches guard rule " + id)
            .or(() -> declaredReadOnly ? Optional.empty() : outside);
        if (policy.isPresent()) {
            return ToolDecision.block(POLICY_PREFIX + policy.get() + " (blocked by pai, cannot be approved)");
        }
        ApprovalModeEnum m = mode;
        if (m == ApprovalModeEnum.PLAN && !readOnly) {
            return ToolDecision.block(PLAN_BLOCK);
        }
        String name = tool.definition().name();
        boolean freeReads = m == ApprovalModeEnum.AUTO_READ || m == ApprovalModeEnum.PLAN;
        if (readOnly && !declaredReadOnly && freeReads
            && (m == ApprovalModeEnum.PLAN || !alwaysAllowed.contains(name))) {
            return readCommand(call, tool);
        }
        if (m == ApprovalModeEnum.YOLO || outside.isEmpty() && preApproved(name, readOnly, m)) {
            return ToolDecision.allow();
        }
        asking.lock();
        try {
            if (outside.isEmpty() && alwaysAllowed.contains(name)) {
                return ToolDecision.allow();
            }
            String rendered = outside.map(o -> "note: " + o + "\n").orElse("") + preview.render(call, tool);
            return decide(approver.ask(call, tool, rendered), name);
        } finally {
            asking.unlock();
        }
    }

    /**
     * A call that its tool reports as read-only for this input although the tool is not (a {@code bash} command such as
     * {@code ls}), in a mode with free reads: it runs unless its command may read outside the working directory, then
     * it is asked every time ("always" counts as once).
     */
    private ToolDecision readCommand(ContentBlock.ToolCall call, Tool tool) {
        JsonNode command = call.arguments().path(CommandGuard.COMMAND_ARGUMENT);
        if (command.isTextual() && !PathGuard.commandMayLeaveCwd(command.asText())) {
            return ToolDecision.allow();
        }
        asking.lock();
        try {
            ApprovalDecisionEnum answer = approver.ask(call, tool, READ_OUTSIDE_NOTE + preview.render(call, tool));
            return switch (answer) {
                case ALLOW_ONCE, ALLOW_ALWAYS -> ToolDecision.allow();
                case DENY -> ToolDecision.block(DENIED);
                case NO_ANSWER -> ToolDecision.block(NO_ANSWER);
            };
        } finally {
            asking.unlock();
        }
    }

    private boolean preApproved(String name, boolean readOnly, ApprovalModeEnum m) {
        boolean freeReads = m == ApprovalModeEnum.AUTO_READ || m == ApprovalModeEnum.PLAN;
        return (readOnly && freeReads) || alwaysAllowed.contains(name);
    }

    private ToolDecision decide(ApprovalDecisionEnum answer, String name) {
        return switch (answer) {
            case ALLOW_ONCE -> ToolDecision.allow();
            case ALLOW_ALWAYS -> {
                alwaysAllowed.add(name);
                yield ToolDecision.allow();
            }
            case DENY -> ToolDecision.block(DENIED);
            case NO_ANSWER -> ToolDecision.block(NO_ANSWER);
        };
    }

    ApprovalModeEnum mode() {
        return mode;
    }

    /** Switches the mode from the next call on. */
    void mode(ApprovalModeEnum next) {
        mode = next;
    }

    /** Tools approved for the rest of the session, sorted. */
    List<String> alwaysAllowed() {
        return alwaysAllowed.stream().sorted().toList();
    }

    /** Forgets the session's "always" answers. */
    void reset() {
        alwaysAllowed.clear();
    }
}
