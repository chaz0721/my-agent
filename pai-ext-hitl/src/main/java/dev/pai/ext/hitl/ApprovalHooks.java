/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.model.ContentBlock;

/**
 * Decides before every tool call: a guard hit blocks and cannot be approved; then the mode, the tools allowed for the
 * whole session and finally the {@link Approver} decide. The loop may call this from several threads; the approver is
 * asked one call at a time.
 */
final class ApprovalHooks implements AgentHooks {

    static final String POLICY_PREFIX = "policy: ";
    static final String DENIED = "user denied";
    static final String NO_ANSWER = "approval required but nobody answered (pai is not interactive or input ended); "
        + "start pai interactively or with PAI_APPROVAL=auto-read or yolo";

    private final ApprovalModeEnum mode;
    private final PathGuard paths;
    private final CommandGuard commands;
    private final Approver approver;
    private final CallPreview preview;
    private final Set<String> alwaysAllowed = ConcurrentHashMap.newKeySet();

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
        Optional<String> rule = commands.check(call).map(id -> "command matches guard rule " + id);
        Optional<String> policy = rule.or(() -> paths.check(call, tool));
        if (policy.isPresent()) {
            return ToolDecision.block(POLICY_PREFIX + policy.get() + " (blocked by pai, cannot be approved)");
        }
        String name = tool.definition().name();
        boolean readOnly = tool.effect() == ToolEffectEnum.READ_ONLY;
        if (mode == ApprovalModeEnum.YOLO || readOnly && mode == ApprovalModeEnum.AUTO_READ
            || alwaysAllowed.contains(name)) {
            return ToolDecision.allow();
        }
        return switch (approver.ask(call, tool, preview.render(call, tool))) {
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

    /** Tools approved for the rest of the session, sorted. */
    List<String> alwaysAllowed() {
        return alwaysAllowed.stream().sorted().toList();
    }

    /** Forgets the session's "always" answers. */
    void reset() {
        alwaysAllowed.clear();
    }
}
