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

/** Everything the loop publishes. Consumers (renderers, session writers, extensions) only see these events. */
public sealed interface AgentEvent
    permits AgentEvent.AgentStart, AgentEvent.AgentEnd, AgentEvent.TurnStart, AgentEvent.TurnEnd,
    AgentEvent.MessageStart, AgentEvent.MessageUpdate, AgentEvent.MessageEnd, AgentEvent.ToolExecutionStart,
    AgentEvent.ToolExecutionUpdate, AgentEvent.ToolExecutionEnd, AgentEvent.ContextCompacted, AgentEvent.Error {

    /** A run started. */
    record AgentStart() implements AgentEvent {
    }

    /**
     * A run ended.
     *
     * @param newMessages every message the run appended, in order
     * @param reason why it ended
     */
    record AgentEnd(List<Message> newMessages, TerminationReasonEnum reason) implements AgentEvent {

        /** Copies the list. */
        public AgentEnd {
            newMessages = List.copyOf(newMessages);
            Objects.requireNonNull(reason, "reason");
        }

        /** A completed run (the constructor before M7). */
        public AgentEnd(List<Message> newMessages) {
            this(newMessages, TerminationReasonEnum.COMPLETED);
        }
    }

    /**
     * A turn (model reply plus its tool executions) started; emitted just before the request is sent.
     *
     * @param turnIndex zero-based turn number within the run
     * @param estimatedTokens estimated size of the request (context as sent, system prompt and tools)
     */
    record TurnStart(int turnIndex, int estimatedTokens) implements AgentEvent {

        /** A turn of unknown request size (the constructor before M7). */
        public TurnStart(int turnIndex) {
            this(turnIndex, 0);
        }
    }

    /**
     * A turn ended.
     *
     * @param message the assistant reply of the turn
     * @param toolResults results in tool call order
     */
    record TurnEnd(AssistantMessage message, List<ToolResultMessage> toolResults) implements AgentEvent {

        /** Copies the list. */
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
     * The context was compacted; only the loop emits it: kind {@code SUMMARY} without summary when the
     * {@code transformContext} hooks replace the context, and the kind and summary of a {@link ContextStage} each time
     * the stage takes effect ({@code MICRO}: the request carries a projection, the kept context is unchanged).
     *
     * @param messagesBefore message count before
     * @param messagesAfter message count after
     * @param kind what was compacted
     * @param summary the summary that now replaces the early messages, when known
     */
    record ContextCompacted(int messagesBefore, int messagesAfter, KindEnum kind,
        Optional<String> summary) implements AgentEvent {

        /** Validates fields. */
        public ContextCompacted {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(summary, "summary");
        }

        /**
         * A summary compaction without known summary text.
         *
         * @param messagesBefore message count before
         * @param messagesAfter message count after
         */
        public ContextCompacted(int messagesBefore, int messagesAfter) {
            this(messagesBefore, messagesAfter, KindEnum.SUMMARY, Optional.empty());
        }

        /** Kinds of compaction. */
        public enum KindEnum {
            /** Early tool results replaced by placeholders in the request only; nothing recorded. */
            MICRO,
            /** Early messages replaced by a recorded summary. */
            SUMMARY
        }
    }

    /**
     * The run failed or was cancelled; the loop stops after this event.
     *
     * @param message diagnostic
     * @param cause underlying exception, if any
     */
    record Error(String message, Optional<Throwable> cause) implements AgentEvent {

        /** Validates fields. */
        public Error {
            Objects.requireNonNull(message, "message");
            cause = cause == null ? Optional.empty() : cause;
        }
    }
}
