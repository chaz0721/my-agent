/* pai-next — Apache-2.0 */
package dev.pai.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A provider-neutral message sent to or received from a model (ADR 0001).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = SystemMessage.class, name = "system"),
    @JsonSubTypes.Type(value = UserMessage.class, name = "user"),
    @JsonSubTypes.Type(value = AssistantMessage.class, name = "assistant"),
    @JsonSubTypes.Type(value = ToolResultMessage.class, name = "tool_result")})
public sealed interface Message permits SystemMessage, UserMessage, AssistantMessage, ToolResultMessage {}
