/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.util.Objects;
import java.util.Optional;

/**
 * One increment of a streaming model reply.
 *
 * <p>
 * Protocol: {@link Start} comes first; exactly one {@link Done} or {@link Error} comes last. {@code index} is the
 * content block index in the final message; blocks are opened in increasing index order and no event refers to a block
 * after its {@link ToolCallEnd}. {@link ToolCallEnd#toolCall()} always carries a JSON object; arguments that fail to
 * parse become {@code {}} and a diagnostic is recorded in the final message's {@code toolCallDiagnostics}.
 */
public sealed interface StreamEvent permits StreamEvent.Start, StreamEvent.TextDelta, StreamEvent.ThinkingDelta,
    StreamEvent.ToolCallStart, StreamEvent.ToolCallDelta, StreamEvent.ToolCallEnd, StreamEvent.Done, StreamEvent.Error {

    /**
     * The stream has started.
     */
    record Start() implements StreamEvent {
    }

    /**
     * Text appended to the text block at {@code index}.
     *
     * @param index content block index
     * @param delta appended text
     */
    record TextDelta(int index, String delta) implements StreamEvent {
    }

    /**
     * Reasoning appended to the thinking block at {@code index}.
     *
     * @param index content block index
     * @param delta appended reasoning
     */
    record ThinkingDelta(int index, String delta) implements StreamEvent {
    }

    /**
     * A tool call block opened at {@code index}.
     *
     * @param index content block index
     * @param id call id
     * @param name tool name
     */
    record ToolCallStart(int index, String id, String name) implements StreamEvent {
    }

    /**
     * A raw fragment of the tool call arguments JSON.
     *
     * @param index content block index
     * @param argumentsJsonDelta partial JSON text
     */
    record ToolCallDelta(int index, String argumentsJsonDelta) implements StreamEvent {
    }

    /**
     * The tool call block at {@code index} is complete; arguments are parsed.
     *
     * @param index content block index
     * @param toolCall the finished tool call
     */
    record ToolCallEnd(int index, ContentBlock.ToolCall toolCall) implements StreamEvent {
    }

    /**
     * Successful end of stream.
     *
     * @param message the final message
     */
    record Done(AssistantMessage message) implements StreamEvent {
    }

    /**
     * Failed or cancelled end of stream.
     *
     * @param reason {@link StopReasonEnum#ERROR} or {@link StopReasonEnum#ABORTED}
     * @param message diagnostic
     * @param partial what had been received before the failure
     */
    record Error(StopReasonEnum reason, String message, Optional<AssistantMessage> partial) implements StreamEvent {

        /**
         * Validates fields.
         */
        public Error {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
            partial = partial == null ? Optional.empty() : partial;
        }
    }
}
