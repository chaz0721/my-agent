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
 * @param retryOnceOnContextOverflow whether a request rejected as too long is retried once after forced compaction
 */
public record AgentConfig(ModelRef model, String systemPrompt, ChatOptions options, int maxToolConcurrency,
    int maxTurns, boolean retryOnceOnContextOverflow) {

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
     * Creates a configuration that retries once on context overflow.
     *
     * @param model the model
     * @param systemPrompt the system prompt
     * @param options request options
     * @param maxToolConcurrency parallelism bound
     * @param maxTurns turn bound
     */
    public AgentConfig(ModelRef model, String systemPrompt, ChatOptions options, int maxToolConcurrency, int maxTurns) {
        this(model, systemPrompt, options, maxToolConcurrency, maxTurns, true);
    }

    /**
     * Returns a copy using another model.
     *
     * @param m the model
     * @return the new configuration
     */
    public AgentConfig withModel(ModelRef m) {
        return new AgentConfig(m, systemPrompt, options, maxToolConcurrency, maxTurns, retryOnceOnContextOverflow);
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
