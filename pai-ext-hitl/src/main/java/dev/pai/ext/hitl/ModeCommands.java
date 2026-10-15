/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.pai.core.ext.CommandContext;
import dev.pai.core.ext.CommandResult;
import dev.pai.core.ext.SlashCommand;

/**
 * The slash commands of the approvals extension: {@code /approvals} and {@code /plan}.
 */
final class ModeCommands {

    static final String YOLO_REFUSED =
        "approvals: yolo can only be chosen at startup (--approval yolo, PAI_APPROVAL " + "or config.json)";

    private ModeCommands() {}

    static List<SlashCommand> of(ApprovalHooks hooks, PlanMode plan) {
        return List.of(new ApprovalsCommand(hooks, plan), new PlanCommand(plan));
    }

    private static CommandResult out(String text) {
        return new CommandResult(text, false);
    }

    /**
     * {@code /approvals [reset|cycle|ask|auto-read|plan]} and the {@link RuleCommands}; {@code cycle} is what Shift+Tab
     * sends.
     */
    private static final class ApprovalsCommand implements SlashCommand {
        private final ApprovalHooks hooks;
        private final PlanMode plan;

        private ApprovalsCommand(ApprovalHooks hooks, PlanMode plan) {
            this.hooks = hooks;
            this.plan = plan;
        }

        @Override
        public String name() {
            return "approvals";
        }

        @Override
        public String description() {
            return "Show the approval mode; /approvals ask|auto-read|plan|cycle switches it, /approvals reset forgets "
                + "tools allowed this session, /approvals list|allow|deny|forget edits saved rules";
        }

        @Override
        public CommandResult run(String args, CommandContext ctx) {
            String a = args.strip();
            if ("reset".equals(a)) {
                hooks.reset();
                return out("approvals: session approvals cleared");
            }
            Optional<String> rule = RuleCommands.run(a, hooks.permissions());
            if (rule.isPresent()) {
                return out(rule.get());
            }
            if ("cycle".equals(a)) {
                plan.switchTo(hooks.mode().next());
                return out(modeLine());
            }
            if (!a.isEmpty()) {
                Optional<ApprovalModeEnum> mode = ApprovalModeEnum.parse(a);
                if (mode.isEmpty()) {
                    return out("approvals: unknown mode '" + a + "' (ask, auto-read, plan)");
                }
                if (mode.get() == ApprovalModeEnum.YOLO) {
                    return out(YOLO_REFUSED);
                }
                plan.switchTo(mode.get());
                return out(modeLine());
            }
            List<String> always = hooks.alwaysAllowed();
            return out("approvals: mode " + HitlExtension.modeName(hooks.mode()) + "; always allowed this session: "
                + (always.isEmpty() ? "none" : String.join(", ", always)) + "\n" + HitlExtension.SCOPE_NOTE);
        }

        private String modeLine() {
            return "approvals: mode " + HitlExtension.modeName(hooks.mode());
        }
    }

    /**
     * {@code /plan [off|save]}.
     */
    private static final class PlanCommand implements SlashCommand {
        private final PlanMode plan;

        private PlanCommand(PlanMode plan) {
            this.plan = plan;
        }

        @Override
        public String name() {
            return "plan";
        }

        @Override
        public String description() {
            return "Read-only plan mode; /plan off leaves it, /plan save writes the plan to " + PlanMode.PLAN_FILE
                + " for the next run";
        }

        @Override
        public CommandResult run(String args, CommandContext ctx) {
            return switch (args.strip()) {
                case "" -> {
                    plan.enter();
                    yield out("plan mode on: read-only; ask for a plan, then /plan save and /plan off (or Shift+Tab)");
                }
                case "off" -> out("plan mode off: approvals: mode " + HitlExtension.modeName(plan.leave()));
                case "save" -> save();
                default -> out("usage: /plan [off|save]");
            };
        }

        private CommandResult save() {
            try {
                Optional<Path> file = plan.save();
                return out(file.map(f -> "plan: saved to " + f + "; the next run outside plan mode gets it")
                    .orElse("plan: no plan yet; ask for one in plan mode first"));
            } catch (IOException e) {
                return out("plan: cannot save: " + e.getMessage());
            }
        }
    }
}
