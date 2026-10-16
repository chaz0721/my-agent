/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.ToolDefinition;

/**
 * Decides before every tool call, with the effect the tool reports for the call ({@code Tool.effect(input)}). A command
 * guard hit, or a path outside the working directory for a tool that is not read-only, blocks and cannot be approved. A
 * read-only tool reading outside always asks; so does a read-only command of a tool that is not read-only when its text
 * may name a path outside ({@link PathGuard#commandMayLeaveCwd}). A {@link Permissions} deny rule blocks like a guard
 * (a tool denied as a whole is not even offered); an allow rule runs the call without asking (not in plan mode).
 * Otherwise the mode and the tools allowed for the whole session decide, and finally the {@link Approver}; its
 * {@code ALLOW_SAVE} proposes a rule ({@code bash}: first word and {@code " *"}; other tools: the tool) and saves it
 * once confirmed. A read-only call naming a {@link SensitiveFiles sensitive file} is asked about in every mode but yolo
 * ("always" counts as once). The loop may call this from several threads: the approver is asked one call at a time, and
 * "always" answers given meanwhile are honoured. A call without a {@code command} argument that its tool reports as
 * read-only for this input ({@code spawn_agent} of a read-only agent type, whose own calls come here too) runs like a
 * read-only tool.
 */
final class ApprovalHooks implements AgentHooks {

    static final String POLICY_PREFIX = "policy: ";
    static final String DENIED = "user denied";
    static final String NO_ANSWER = "approval required but nobody answered (pai is not interactive or input ended)";
    static final String READ_OUTSIDE_NOTE =
        "note: this read-only command may read outside the working directory; \"always\" is not remembered\n";
    static final String GIT_UNTRUSTED_NOTE = "note: this project is not trusted and git runs programs its "
        + "configuration names (fsmonitor, diff drivers); \"always\" is not remembered\n";
    static final String UNCERTAIN_NOTE = "note: pai cannot tell what this command runs, and deny rules exist for bash; "
        + "\"always\" is not remembered\n";
    static final String SENSITIVE_NOTE =
        "note: sensitive file (looks like a secret): %s; \"always\" is not " + "remembered\n";
    static final String PLAN_BLOCK = "plan mode: read-only; explore with read-only tools and reply with a plan "
        + "instead (the user leaves plan mode with /plan off or Shift+Tab)";

    private volatile ApprovalModeEnum mode;
    private final PathGuard paths;
    private final CommandGuard commands;
    private final Approver approver;
    private final CallPreview preview;
    private final Permissions permissions;
    private final Consumer<String> notices;
    private final boolean projectTrusted;
    private final SensitiveFiles sensitive = SensitiveFiles.builtin();
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

    /** Hooks for a trusted project without rules (tests). */
    ApprovalHooks(Parts p) {
        this(p, Permissions.none(), n -> {
        }, true);
    }

    /**
     * Creates the hooks.
     *
     * @param p the wiring
     * @param permissions the saved rules
     * @param notices one-line notices for the user
     * @param projectTrusted whether {@code git} read commands may run without asking (in an untrusted repository the
     *            git configuration may name programs that {@code git status} or {@code git diff} run)
     */
    ApprovalHooks(Parts p, Permissions permissions, Consumer<String> notices, boolean projectTrusted) {
        this.projectTrusted = projectTrusted;
        this.mode = p.mode();
        this.paths = p.paths();
        this.commands = p.commands();
        this.approver = p.approver();
        this.preview = p.preview();
        this.permissions = permissions;
        this.notices = notices;
    }

    /** Tools denied as a whole by a rule are not offered to the model. */
    @Override
    public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
        List<ToolDefinition> kept = request.tools().stream().filter(d -> !permissions.deniesTool(d.name())).toList();
        return kept.size() == request.tools().size() ? request
            : new ChatRequest(request.model(), request.systemPrompt(), request.messages(), kept, request.options());
    }

    @Override
    public ToolDecision beforeToolCall(ContentBlock.ToolCall call, Tool tool, LoopState state) {
        boolean declaredReadOnly = tool.effect() == ToolEffectEnum.READ_ONLY;
        boolean readOnly = Tool.effectOf(tool, call.arguments()) == ToolEffectEnum.READ_ONLY;
        Optional<String> outside = paths.check(call);
        Optional<String> policy = commands.check(call).map(id -> "command matches guard rule " + id)
            .or(() -> declaredReadOnly ? Optional.empty() : outside)
            .or(() -> permissions.denying(call).map(r -> "permission rule \"" + r.describe() + "\""));
        if (policy.isPresent()) {
            return ToolDecision.block(POLICY_PREFIX + policy.get() + " (blocked by pai, cannot be approved)");
        }
        ApprovalModeEnum m = mode;
        if (m == ApprovalModeEnum.PLAN && !readOnly) {
            return ToolDecision.block(PLAN_BLOCK);
        }
        if (permissions.uncertain(call)) {
            return askOnce(call, tool, UNCERTAIN_NOTE);
        }
        Optional<String> secret =
            readOnly && m != ApprovalModeEnum.YOLO ? sensitive(call, declaredReadOnly) : Optional.empty();
        if (secret.isPresent()) {
            return askOnce(call, tool, SENSITIVE_NOTE.formatted(secret.get()));
        }
        String name = tool.definition().name();
        boolean freeReads = m == ApprovalModeEnum.AUTO_READ || m == ApprovalModeEnum.PLAN;
        if (readOnly && !declaredReadOnly && freeReads
            && (m == ApprovalModeEnum.PLAN || !alwaysAllowed.contains(name))) {
            if (call.arguments().has(CommandGuard.COMMAND_ARGUMENT)) {
                return readCommand(call, tool);
            }
            if (outside.isEmpty()) {
                return ToolDecision.allow();
            }
        }
        boolean ruled = m != ApprovalModeEnum.PLAN && permissions.allows(call);
        if (m == ApprovalModeEnum.YOLO || outside.isEmpty() && (ruled || preApproved(name, readOnly, m))) {
            return ToolDecision.allow();
        }
        asking.lock();
        try {
            if (outside.isEmpty() && alwaysAllowed.contains(name)) {
                return ToolDecision.allow();
            }
            String rendered = outside.map(o -> "note: " + o + "\n").orElse("") + preview.render(call, tool);
            return decide(approver.ask(call, tool, rendered), call);
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
        String command = call.arguments().path(CommandGuard.COMMAND_ARGUMENT).asText("");
        Optional<String> outside = PathGuard.commandMayLeaveCwd(command) ? Optional.of("")
            : paths.commandOutside(command).map(why -> "note: " + why + "\n");
        if (outside.isPresent()) {
            return askOnce(call, tool, READ_OUTSIDE_NOTE + outside.get());
        }
        if (!projectTrusted && CommandWords.runs(command, "git", Permissions.WINDOWS)) {
            return askOnce(call, tool, GIT_UNTRUSTED_NOTE);
        }
        return ToolDecision.allow();
    }

    /**
     * The sensitive file a read-only call names: the {@code path} argument of a read-only tool, or a word of a
     * read-only command of a tool that is not ({@code cat .env}).
     */
    private Optional<String> sensitive(ContentBlock.ToolCall call, boolean declaredReadOnly) {
        if (declaredReadOnly) {
            String path = call.arguments().path(PathGuard.PATH_ARGUMENT).asText("");
            return sensitive.matches(path, paths.cwd()) ? Optional.of(path) : Optional.empty();
        }
        return sensitive.inCommand(call.arguments().path(CommandGuard.COMMAND_ARGUMENT).asText(""), paths.cwd());
    }

    /** Asks about one call; any yes runs it once ("always" and saving are not remembered). */
    private ToolDecision askOnce(ContentBlock.ToolCall call, Tool tool, String note) {
        asking.lock();
        try {
            ApprovalDecisionEnum answer = approver.ask(call, tool, note + preview.render(call, tool));
            return switch (answer) {
                case ALLOW_ONCE, ALLOW_ALWAYS, ALLOW_SAVE -> ToolDecision.allow();
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

    private ToolDecision decide(ApprovalDecisionEnum answer, ContentBlock.ToolCall call) {
        return switch (answer) {
            case ALLOW_ONCE -> ToolDecision.allow();
            case ALLOW_ALWAYS -> {
                alwaysAllowed.add(call.name());
                yield ToolDecision.allow();
            }
            case ALLOW_SAVE -> {
                save(call);
                yield ToolDecision.allow();
            }
            case DENY -> ToolDecision.block(DENIED);
            case NO_ANSWER -> ToolDecision.block(NO_ANSWER);
        };
    }

    /** Proposes the rule for {@code A}, saves it when the user confirms; the call itself runs either way. */
    private void save(ContentBlock.ToolCall call) {
        Optional<String> pattern = proposedPattern(call);
        if (CallPreview.BASH_TOOL.equals(call.name()) && pattern.isEmpty()) {
            notices.accept("approvals: no rule proposed for this command; it runs once");
            return;
        }
        Permissions.Rule rule = new Permissions.Rule(true, call.name(), pattern, false);
        String where = permissions.userFile().map(Object::toString).orElse("nowhere");
        if (!approver.confirm("save rule \"" + rule.describe() + "\" to " + where + "? later sessions will not ask")) {
            return;
        }
        try {
            permissions.add(true, call.name(), pattern);
            notices.accept("approvals: saved \"" + rule.describe() + "\"");
        } catch (IOException | IllegalArgumentException e) {
            notices.accept("approvals: rule not saved: " + e.getMessage());
        }
    }

    /**
     * For {@code bash} the first word of the command and {@code " *"}, unless the command is compound (the rule would
     * not match it), the word is not plain, or it is an assignment, a wrapper or an interpreter ({@code env},
     * {@code sudo}, {@code sh}, {@code python*}, {@code xargs} ...: a rule for it would allow anything); other tools
     * get no pattern.
     */
    static Optional<String> proposedPattern(ContentBlock.ToolCall call) {
        if (!CallPreview.BASH_TOOL.equals(call.name())) {
            return Optional.empty();
        }
        String command = call.arguments().path(CommandGuard.COMMAND_ARGUMENT).asText("").strip();
        String first = command.split("\\s+", 2)[0];
        boolean plain =
            !Permissions.compound(command) && first.matches("[\\w./:+@=-]+") && !CommandWords.unfitForRule(first);
        return plain ? Optional.of(first + " *") : Optional.empty();
    }

    Permissions permissions() {
        return permissions;
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
