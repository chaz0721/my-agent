/* pai-next — Apache-2.0 */
package dev.pai.core.internal;

import java.util.ArrayList;
import java.util.List;

import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;

/**
 * Hooks combined with the ADR 0005 semantics.
 */
public final class HooksChain implements AgentHooks {

    private final List<AgentHooks> hooks;

    /**
     * Creates the chain.
     *
     * @param hooks hooks in registration order
     */
    public HooksChain(List<AgentHooks> hooks) {
        this.hooks = List.copyOf(hooks);
    }

    @Override
    public ToolDecision beforeToolCall(ContentBlock.ToolCall call, Tool tool, LoopState state) {
        for (AgentHooks h : hooks) {
            ToolDecision d = h.beforeToolCall(call, tool, state);
            if (!d.allowed()) {
                return d;
            }
        }
        return ToolDecision.allow();
    }

    @Override
    public ToolResult afterToolCall(ContentBlock.ToolCall call, ToolResult result, LoopState state) {
        ToolResult r = result;
        for (AgentHooks h : hooks) {
            r = h.afterToolCall(call, r, state);
        }
        return r;
    }

    @Override
    public List<Message> transformContext(List<Message> messages, LoopState state) {
        List<Message> m = messages;
        for (AgentHooks h : hooks) {
            m = h.transformContext(m, state);
        }
        return m;
    }

    @Override
    public List<Message> steeringMessages() {
        List<Message> all = new ArrayList<>();
        for (AgentHooks h : hooks) {
            all.addAll(h.steeringMessages());
        }
        return all;
    }
}
