/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.StreamEvent;
import dev.pai.model.ToolResultMessage;

/**
 * Everything the loop publishes. Consumers (renderers, session writers, extensions) only see these events.
 */
public sealed interface AgentEvent
    permits AgentEvent.AgentStart, AgentEvent.AgentEnd, AgentEvent.TurnStart, AgentEvent.TurnEnd,
    AgentEvent.MessageStart, AgentEvent.MessageUpdate, AgentEvent.MessageEnd, AgentEvent.ToolExecutionStart,
    AgentEvent.ToolExecutionUpdate, AgentEvent.ToolExecutionEnd, AgentEvent.ContextCompacted, AgentEvent.Error {

    /**
     * A run started.
     */
    record AgentStart() implements AgentEvent {
    }

    /**
     * A run ended.
     *
     * @param newMessages every message the run appended, in order
     */
    record AgentEnd(List<Message> newMessages) implements AgentEvent {

        /**
         * Copies the list.
         */
        public AgentEnd {
            newMessages = List.copyOf(newMessages);
        }
    }

    /**
     * A turn (model reply plus its tool executions) started.
     *
     * @param turnIndex zero-based turn number within the run
     */
    record TurnStart(int turnIndex) implements AgentEvent {
    }

    /**
     * A turn ended.
     *
     * @param message the assistant reply of the turn
     * @param toolResults results in tool call order
     */
    record TurnEnd(AssistantMessage message, List<ToolResultMessage> toolResults) implements AgentEvent {

        /**
         * Copies the list.
         */
        public TurnEnd {
            toolResults = List.copyOf(toolResults);
        }
    }

    /**
     * A message was appended (user, tool result) or an assistant reply began streaming (empty shell).
     *
     * @param message the message
     */
    record MessageStart(Message message) implements AgentEvent {
    }

    /**
     * A stream increment of the assistant reply.
     *
     * @param event the provider event
     */
    record MessageUpdate(StreamEvent event) implements AgentEvent {
    }

    /**
     * A message is complete.
     *
     * @param message the message
     */
    record MessageEnd(Message message) implements AgentEvent {
    }

    /**
     * A tool call starts executing.
     *
     * @param call the call
     */
    record ToolExecutionStart(ContentBlock.ToolCall call) implements AgentEvent {
    }

    /**
     * Progress reported by a running tool.
     *
     * @param toolCallId the call id
     * @param partial progress text
     */
    record ToolExecutionUpdate(String toolCallId, String partial) implements AgentEvent {
    }

    /**
     * A tool call finished. Under parallel execution these may arrive out of call order.
     *
     * @param call the call
     * @param result the result after hooks
     * @param took execution time
     */
    record ToolExecutionEnd(ContentBlock.ToolCall call, ToolResult result, Duration took) implements AgentEvent {
    }

    /**
     * The context was compacted (emitted from M2 on).
     *
     * @param messagesBefore message count before
     * @param messagesAfter message count after
     */
    record ContextCompacted(int messagesBefore, int messagesAfter) implements AgentEvent {
    }

    /**
     * The run failed or was cancelled; the loop stops after this event.
     *
     * @param message diagnostic
     * @param cause underlying exception, if any
     */
    record Error(String message, Optional<Throwable> cause) implements AgentEvent {

        /**
         * Validates fields.
         */
        public Error {
            Objects.requireNonNull(message, "message");
            cause = cause == null ? Optional.empty() : cause;
        }
    }
}
