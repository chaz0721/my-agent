/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.util.List;

import dev.pai.core.ext.UserInteraction;
import dev.pai.core.tool.Tool;
import dev.pai.model.ContentBlock;

/**
 * Asks the user at the terminal: {@code y} runs the call, {@code a} runs it and every later call of the tool this
 * session, {@code n} denies it.
 */
final class InteractiveApproverImpl implements Approver {

    static final String YES = "y";
    static final String NO = "n";
    static final String ALWAYS = "a";
    private static final List<String> CHOICES = List.of(YES, NO, ALWAYS);

    private final UserInteraction ui;

    InteractiveApproverImpl(UserInteraction ui) {
        this.ui = ui;
    }

    @Override
    public synchronized ApprovalDecisionEnum ask(ContentBlock.ToolCall call, Tool tool, String rendered) {
        String answer = ui.ask(
            rendered + "\nallow? y = yes, n = no, a = always for " + tool.definition().name() + " in this session",
            CHOICES);
        return switch (answer) {
            case YES -> ApprovalDecisionEnum.ALLOW_ONCE;
            case ALWAYS -> ApprovalDecisionEnum.ALLOW_ALWAYS;
            case NO -> ApprovalDecisionEnum.DENY;
            default -> ApprovalDecisionEnum.NO_ANSWER;
        };
    }
}
