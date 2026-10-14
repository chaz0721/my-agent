/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

/**
 * A concrete model of a provider together with its limits and capabilities.
 *
 * @param providerId provider id
 * @param modelId model id as the provider names it
 * @param baseUrl API base URL
 * @param contextWindow context window in tokens
 * @param maxOutputTokens default output token limit
 * @param capabilities what the model supports
 */
public record ModelRef(String providerId, String modelId, URI baseUrl, int contextWindow, int maxOutputTokens,
    Set<CapabilityEnum> capabilities) {

    /**
     * Copies the capability set and validates fields.
     */
    public ModelRef {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        capabilities = Set.copyOf(capabilities);
    }

    /**
     * Model capabilities used by codecs to choose encodings and degradations.
     */
    public enum CapabilityEnum {
        /** Supports tool calling. */
        TOOLS,
        /** Accepts image input. */
        IMAGE_INPUT,
        /** Produces reasoning blocks. */
        THINKING,
        /** Supports prompt caching. */
        PROMPT_CACHE
    }
}
