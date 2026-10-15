/* pai-next — Apache-2.0 */
package dev.pai.model.preset;

import java.util.Map;

/**
 * Quirks of an OpenAI Chat Completions compatible endpoint.
 *
 * @param forceHttp1 restrict the transport to HTTP/1.1 (DeepSeek resets long HTTP/2 streams)
 * @param echoReasoning send reasoning text back on assistant messages of the same provider and model (DeepSeek, Kimi)
 * @param imageInput the endpoint accepts image content; models of the preset get the image capability
 * @param sendTools the endpoint accepts tool definitions; without it models of the preset lose the tools capability, so
 *            the loop offers no tools
 * @param extraHeaders additional request headers, for example {@code lora_id} for a fine-tuned xfyun model
 */
public record ProviderFlags(boolean forceHttp1, boolean echoReasoning, boolean imageInput, boolean sendTools,
    Map<String, String> extraHeaders) {

    /** Plain endpoint: tools, no images, no reasoning echo. */
    public static final ProviderFlags NONE = new ProviderFlags(false, false, false, true, Map.of());

    /**
     * Copies the header map.
     */
    public ProviderFlags {
        extraHeaders = Map.copyOf(extraHeaders);
    }
}
