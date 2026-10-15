/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import dev.pai.core.tool.Tool;
import dev.pai.model.ContentBlock;

/**
 * Asks whether one tool call may run. Calls may come from several threads; implementations ask one at a time.
 */
public interface Approver {

    /**
     * Asks about a call.
     *
     * @param call the tool call
     * @param tool the tool it invokes
     * @param rendered what the call will do, ready to show (command, diff or full arguments)
     * @return the decision
     */
    ApprovalDecisionEnum ask(ContentBlock.ToolCall call, Tool tool, String rendered);
}
