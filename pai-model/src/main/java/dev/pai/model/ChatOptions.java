/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Per-request options.
 *
 * @param maxTokens output token limit; the model default when empty
 * @param temperature sampling temperature
 * @param thinking requested reasoning effort
 * @param providerExtras fields merged verbatim into the request body (for example top_p)
 */
public record ChatOptions(OptionalInt maxTokens, OptionalDouble temperature, ThinkingLevelEnum thinking,
    Map<String, JsonNode> providerExtras) {

    /**
     * Copies the extras and validates fields.
     */
    public ChatOptions {
        Objects.requireNonNull(maxTokens, "maxTokens");
        Objects.requireNonNull(temperature, "temperature");
        Objects.requireNonNull(thinking, "thinking");
        providerExtras = Map.copyOf(providerExtras);
    }

    /**
     * Returns options with every field at its default.
     *
     * @return default options
     */
    public static ChatOptions defaults() {
        return new ChatOptions(OptionalInt.empty(), OptionalDouble.empty(), ThinkingLevelEnum.OFF, Map.of());
    }

    /**
     * Requested reasoning effort.
     */
    public enum ThinkingLevelEnum {
        /** No reasoning requested. */
        OFF,
        /** Small reasoning budget. */
        LOW,
        /** Medium reasoning budget. */
        MEDIUM,
        /** Large reasoning budget. */
        HIGH
    }
}
