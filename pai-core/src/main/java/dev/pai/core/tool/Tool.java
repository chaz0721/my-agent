/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.ToolDefinition;

/** A capability the model can invoke: definition, effect metadata and execution in one object (ADR 0002). */
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
     * Returns the effect of one call: {@link #effect()} or something safer; decisions read it through
     * {@link #effectOf}.
     *
     * @param input the call arguments
     * @return the effect of this call, {@link #effect()} by default
     */
    default ToolEffectEnum effect(JsonNode input) {
        return effect();
    }

    /**
     * Returns the effect to decide with: {@code tool.effect(input)} when it is {@link #effect()} or {@code READ_ONLY};
     * otherwise the more dangerous of the two (for a tool declared {@code READ_ONLY} that is the refined effect, else
     * the declared one), with a warning. When {@code effect(input)} throws or returns null: {@link #effect()}, or
     * {@code UNKNOWN} for a tool declared {@code READ_ONLY} (fail-closed).
     *
     * @param tool the tool
     * @param input the call arguments
     * @return the effect to decide with
     */
    static ToolEffectEnum effectOf(Tool tool, JsonNode input) {
        ToolEffectEnum declared = tool.effect();
        ToolEffectEnum failed = declared == ToolEffectEnum.READ_ONLY ? ToolEffectEnum.UNKNOWN : declared;
        ToolEffectEnum refined;
        try {
            refined = tool.effect(input);
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(Tool.class).warn("effect(input) of {} failed", tool.definition().name(), e);
            return failed;
        }
        if (refined == null) {
            return failed;
        }
        if (refined == declared || refined == ToolEffectEnum.READ_ONLY) {
            return refined;
        }
        LoggerFactory.getLogger(Tool.class).warn("effect(input) of {} is {}, more than {}", tool.definition().name(),
            refined, declared);
        return declared == ToolEffectEnum.READ_ONLY ? refined : declared;
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
     * Returns whether one call may run in parallel with other concurrency-safe calls; a tool whose
     * {@link #effect(JsonNode)} makes some calls read-only overrides this.
     *
     * @param input the call arguments
     * @return by default {@link #concurrencySafe()} and the call is read-only ({@link #effectOf})
     */
    default boolean concurrencySafe(JsonNode input) {
        return concurrencySafe() && effectOf(this, input) == ToolEffectEnum.READ_ONLY;
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
