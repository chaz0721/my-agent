/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a provider needs for one streaming request.
 *
 * @param model target model
 * @param systemPrompt system prompt, if any
 * @param messages conversation context
 * @param tools tool definitions offered to the model
 * @param options sampling and provider options
 */
public record ChatRequest(ModelRef model, Optional<String> systemPrompt, List<Message> messages,
    List<ToolDefinition> tools, ChatOptions options) {

    /**
     * Copies collections and validates fields.
     */
    public ChatRequest {
        Objects.requireNonNull(model, "model");
        systemPrompt = systemPrompt == null ? Optional.empty() : systemPrompt;
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        Objects.requireNonNull(options, "options");
    }
}
