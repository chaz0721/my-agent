/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.util.List;

import dev.pai.core.internal.HooksChain;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;

/**
 * The closed set of points where extensions intervene in the loop (ADR 0003).
 */
public interface AgentHooks {

    /**
     * Decides whether a tool call may run. Called sequentially, in call order, before any execution of the turn.
     *
     * @param call the call
     * @param tool the resolved tool
     * @param state loop snapshot
     * @return the decision
     */
    default ToolDecision beforeToolCall(ContentBlock.ToolCall call, Tool tool, LoopState state) {
        return ToolDecision.allow();
    }

    /**
     * Rewrites a tool result before it is fed back.
     *
     * @param call the call
     * @param result the result
     * @param state loop snapshot
     * @return the result to use
     */
    default ToolResult afterToolCall(ContentBlock.ToolCall call, ToolResult result, LoopState state) {
        return result;
    }

    /**
     * Rewrites the context before each model request (compaction happens here).
     *
     * @param messages current context
     * @param state loop snapshot
     * @return the context to send and keep
     */
    default List<Message> transformContext(List<Message> messages, LoopState state) {
        return messages;
    }

    /**
     * Returns user messages to inject after the current turn.
     *
     * @return messages, usually empty
     */
    default List<Message> steeringMessages() {
        return List.of();
    }

    /**
     * Chains hooks: beforeToolCall short-circuits on the first block; afterToolCall and transformContext fold in order;
     * steering messages are concatenated.
     *
     * @param hooks hooks in registration order
     * @return the chain
     */
    static AgentHooks chain(List<AgentHooks> hooks) {
        return new HooksChain(hooks);
    }
}
