/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.StreamEvent;

/**
 * A scripted event sequence: reasoning, text, one read call and its result, end of run.
 */
final class Events {

    static final ContentBlock.ToolCall READ =
        new ContentBlock.ToolCall("c1", "read", Json.mapper().createObjectNode().put("path", "src/Main.java"));

    private Events() {}

    static List<AgentEvent> run() {
        AssistantMessage shell = FakeProviderImpl.reply(List.of(), StopReasonEnum.STOP);
        ToolResult result = ToolResult.text("1\tclass Main {\n2\t}\n3\t");
        return List.of(new AgentEvent.AgentStart(), new AgentEvent.TurnStart(0), new AgentEvent.MessageStart(shell),
            new AgentEvent.MessageUpdate(new StreamEvent.ThinkingDelta(0, "first thought\nsecond\nthird\nfourth")),
            new AgentEvent.MessageUpdate(new StreamEvent.TextDelta(1, "Reading the file")),
            new AgentEvent.MessageEnd(shell), new AgentEvent.ToolExecutionStart(READ),
            new AgentEvent.ToolExecutionEnd(READ, result, Duration.ofMillis(30)),
            new AgentEvent.ContextCompacted(12, 5), new AgentEvent.Error("boom", Optional.empty()),
            new AgentEvent.AgentEnd(List.of(shell)));
    }
}
