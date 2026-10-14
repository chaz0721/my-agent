/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.util.Objects;

import dev.pai.model.ChatOptions;
import dev.pai.model.ModelRef;

/**
 * Loop configuration.
 *
 * @param model the model
 * @param systemPrompt system prompt sent with every request
 * @param options request options
 * @param maxToolConcurrency upper bound of parallel concurrency-safe tool calls
 * @param maxTurns upper bound of turns per run
 */
public record AgentConfig(ModelRef model, String systemPrompt, ChatOptions options, int maxToolConcurrency,
    int maxTurns) {

    /** Default {@link #maxToolConcurrency()}. */
    public static final int DEFAULT_MAX_TOOL_CONCURRENCY = 4;
    /** Default {@link #maxTurns()}. */
    public static final int DEFAULT_MAX_TURNS = 50;

    /**
     * Validates fields.
     */
    public AgentConfig {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(options, "options");
        if (maxToolConcurrency < 1 || maxTurns < 1) {
            throw new IllegalArgumentException("maxToolConcurrency and maxTurns must be positive");
        }
    }

    /**
     * Creates a configuration with default options and limits.
     *
     * @param model the model
     * @param systemPrompt the system prompt
     * @return the configuration
     */
    public static AgentConfig of(ModelRef model, String systemPrompt) {
        return new AgentConfig(model, systemPrompt, ChatOptions.defaults(), DEFAULT_MAX_TOOL_CONCURRENCY,
            DEFAULT_MAX_TURNS);
    }
}
