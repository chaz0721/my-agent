/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.util.List;

import dev.pai.core.internal.HooksChain;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;

/**
 * The closed set of points where extensions intervene in the loop (ADR 0003).
 *
 * <p>
 * Threading: {@link #beforeToolCall} runs on the loop thread for every call of a turn, in call order, before any of
 * them executes (sequential and parallel mode alike). {@link #afterToolCall} runs right after each execution: on the
 * loop thread in sequential mode, on a worker thread in parallel mode, so implementations must be thread-safe.
 * {@link #transformContext}, {@link #prepareRequest} and {@link #steeringMessages} run on the loop thread. Exceptions
 * are isolated: a failing beforeToolCall blocks the call, a failing afterToolCall keeps the original result, a failing
 * transformContext ends the run with an {@link AgentEvent.Error}, a failing prepareRequest is skipped (the request
 * passes on unchanged to the next hook), a failing steeringMessages is treated as no messages.
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
     * Rewrites the context before each model request (compaction happens here). Return the given list itself to leave
     * the context alone: a result of another length, or with any element that is not the identical object, counts as a
     * compaction and makes the loop emit {@link AgentEvent.ContextCompacted}.
     *
     * @param messages current context
     * @param state loop snapshot
     * @return the context to send and keep
     */
    default List<Message> transformContext(List<Message> messages, LoopState state) {
        return messages;
    }

    /**
     * Rewrites a request right before it is sent to the provider (system prompt sections, provider options). Called
     * once per provider call, after {@link #transformContext}; the context it sees is not kept.
     *
     * @param request the request about to be sent
     * @param state loop snapshot
     * @return the request to send
     */
    default ChatRequest prepareRequest(ChatRequest request, LoopState state) {
        return request;
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
     * Chains hooks: beforeToolCall short-circuits on the first block; afterToolCall, transformContext and
     * prepareRequest fold in order; steering messages are concatenated.
     *
     * @param hooks hooks in registration order
     * @return the chain
     */
    static AgentHooks chain(List<AgentHooks> hooks) {
        return new HooksChain(hooks);
    }
}
