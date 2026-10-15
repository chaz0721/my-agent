/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import dev.pai.core.tool.Tool;
import dev.pai.model.ContentBlock;

/**
 * Used when nobody can be asked (pai runs non-interactively): every request goes unanswered.
 */
final class DenyingApproverImpl implements Approver {

    @Override
    public ApprovalDecisionEnum ask(ContentBlock.ToolCall call, Tool tool, String rendered) {
        return ApprovalDecisionEnum.NO_ANSWER;
    }
}
