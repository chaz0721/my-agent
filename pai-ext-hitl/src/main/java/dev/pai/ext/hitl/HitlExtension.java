/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.pai.core.ext.CommandContext;
import dev.pai.core.ext.CommandResult;
import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.ext.SlashCommand;

/**
 * Approval of tool calls. In the default {@link ApprovalModeEnum#AUTO_READ} mode read-only tools run without asking
 * while their {@code path} argument stays inside the working directory; every other call is shown (command, diff or
 * full arguments) and needs {@code y}, {@code a} (always for that tool this session) or {@code n}. Writes outside the
 * working directory and commands matching a guard rule are blocked and cannot be approved. The path guard only looks at
 * the {@code path} argument: what a {@code bash} command touches is covered by approval and the command guard alone. A
 * tool that is not read-only (MCP tools included) with a {@code path} outside is blocked too; list extra directories in
 * {@code allowDirs} of {@code ~/.pai/ext/hitl/config.json} to permit them. Without a terminal nobody can approve, so
 * such calls are denied. {@code /approvals} shows the mode; {@code /approvals reset} forgets "always".
 */
public final class HitlExtension implements Extension {

    /** Extension id. */
    public static final String ID = "hitl";

    /** Shown by {@code /approvals}: what the guards cover. */
    static final String SCOPE_NOTE = "path guard checks only the path argument of a tool; bash commands rely on "
        + "approval and the command guard; a non-read-only tool (MCP included) with a path outside is blocked, "
        + "allow directories with allowDirs in ~/.pai/ext/hitl/config.json";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onLoad(ExtensionContext ctx) {
        HitlSettings settings = HitlSettings.load(ctx.configDir(),
            Optional.ofNullable(System.getProperty(HitlSettings.MODE_PROPERTY)), System.getenv());
        settings.problems().forEach(p -> ctx.notify("approvals: " + p));
        Approver approver = ctx.ui().<Approver>map(InteractiveApproverImpl::new).orElseGet(DenyingApproverImpl::new);
        if (ctx.ui().isEmpty() && settings.mode() != ApprovalModeEnum.YOLO) {
            ctx.notify("approvals: mode " + modeName(settings.mode()) + " but no terminal to ask; calls that need "
                + "approval are denied");
        }
        ApprovalHooks hooks =
            new ApprovalHooks(new ApprovalHooks.Parts(settings.mode(), new PathGuard(ctx.cwd(), settings.allowDirs()),
                CommandGuard.builtin(), approver, new CallPreview(ctx.cwd())));
        ctx.addHooks(hooks);
        ctx.registerCommand(new ApprovalsCommand(hooks));
        ctx.onSession(SessionEvent.SessionStarted.class, e -> hooks.reset());
        if (settings.mode() == ApprovalModeEnum.YOLO) {
            ctx.notify("approvals: yolo mode - tool calls run without asking (guards still block)");
        }
    }

    static String modeName(ApprovalModeEnum mode) {
        return mode.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * {@code /approvals [reset]}.
     */
    private static final class ApprovalsCommand implements SlashCommand {
        private final ApprovalHooks hooks;

        private ApprovalsCommand(ApprovalHooks hooks) {
            this.hooks = hooks;
        }

        @Override
        public String name() {
            return "approvals";
        }

        @Override
        public String description() {
            return "Show the approval mode and tools allowed this session; /approvals reset forgets them";
        }

        @Override
        public CommandResult run(String args, CommandContext ctx) {
            if ("reset".equals(args.strip())) {
                hooks.reset();
                return new CommandResult("approvals: session approvals cleared", false);
            }
            List<String> always = hooks.alwaysAllowed();
            return new CommandResult("approvals: mode " + modeName(hooks.mode()) + "; always allowed this session: "
                + (always.isEmpty() ? "none" : String.join(", ", always)) + "\n" + SCOPE_NOTE, false);
        }
    }
}
