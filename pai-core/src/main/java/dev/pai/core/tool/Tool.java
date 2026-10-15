/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.ToolDefinition;

/**
 * A capability the model can invoke: definition, effect metadata and execution in one object (ADR 0002).
 */
public interface Tool {

    /**
     * Returns what the model sees: name, description and argument schema.
     *
     * @return the definition
     */
    ToolDefinition definition();

    /**
     * Returns the side-effect category, used for concurrency and approval decisions.
     *
     * @return the effect
     */
    ToolEffectEnum effect();

    /**
     * Returns the effect of one call; it may only be the same as {@link #effect()} or {@code READ_ONLY}, otherwise
     * callers use {@link #effect()} ({@link ToolEffects#of}).
     *
     * @param input the call arguments
     * @return the effect of this call, {@link #effect()} by default
     */
    default ToolEffectEnum effect(JsonNode input) {
        return effect();
    }

    /**
     * Returns whether this tool may run in parallel with other concurrency-safe tools.
     *
     * @return true for read-only tools by default
     */
    default boolean concurrencySafe() {
        return effect() == ToolEffectEnum.READ_ONLY;
    }

    /**
     * Returns whether one call may run in parallel with other concurrency-safe calls.
     *
     * @param input the call arguments
     * @return true when the call is read-only by default
     */
    default boolean concurrencySafe(JsonNode input) {
        return effect(input) == ToolEffectEnum.READ_ONLY;
    }

    /**
     * Runs the tool. A thrown exception becomes an error result whose text is the exception message.
     *
     * @param arguments parsed arguments object, already checked against the schema's required fields and types
     * @param ctx working directory, cancellation and progress reporting
     * @return the result
     * @throws Exception on failure
     */
    ToolResult execute(JsonNode arguments, ToolContext ctx) throws Exception;
}
