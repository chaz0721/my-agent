/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Fail-closed reading of the per-call metadata of a {@link Tool} (ADR 0002, M7 revision).
 */
public final class ToolEffects {

    private static final Logger log = LoggerFactory.getLogger(ToolEffects.class);

    private ToolEffects() {}

    /**
     * Returns {@link Tool#effect(JsonNode)} when it is {@link Tool#effect()} or {@code READ_ONLY}; when it throws or
     * claims a more dangerous effect than the static one, logs a warning and returns {@link Tool#effect()}.
     *
     * @param tool the tool
     * @param input the call arguments
     * @return the effect to decide with
     */
    public static ToolEffectEnum of(Tool tool, JsonNode input) {
        ToolEffectEnum declared = tool.effect();
        ToolEffectEnum refined;
        try {
            refined = tool.effect(input);
        } catch (RuntimeException e) {
            log.warn("effect(input) of {} failed; using {}", tool.definition().name(), declared, e);
            return declared;
        }
        if (refined == declared || refined == ToolEffectEnum.READ_ONLY) {
            return refined;
        }
        log.warn("effect(input) of {} returned {}, more than {}; using {}", tool.definition().name(), refined, declared,
            declared);
        return declared;
    }

    /**
     * Returns {@link Tool#concurrencySafe(JsonNode)}, or false when it throws.
     *
     * @param tool the tool
     * @param input the call arguments
     * @return whether the call may run in parallel
     */
    public static boolean concurrencySafe(Tool tool, JsonNode input) {
        try {
            return tool.concurrencySafe(input);
        } catch (RuntimeException e) {
            log.warn("concurrencySafe(input) of {} failed; running it alone", tool.definition().name(), e);
            return false;
        }
    }
}
