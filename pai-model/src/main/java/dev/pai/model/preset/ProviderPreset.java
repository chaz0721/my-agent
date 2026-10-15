/* pai-next — Apache-2.0 */
package dev.pai.model.preset;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.pai.model.ModelRef;

/**
 * A known provider endpoint: where it is, which variables hold its key, its quirks and its models.
 *
 * @param id provider id, for example {@code deepseek}
 * @param api wire protocol; {@value #OPENAI_COMPLETIONS} is the only one presets use
 * @param baseUrl URL prefix the completions path is appended to
 * @param apiKeyEnv variables that may hold the key, the preset's own first; later ones are shared keys that are only
 *            used when their base URL (if set) points at this preset's host
 * @param flags endpoint quirks
 * @param models models, each with this preset's id and base URL
 */
public record ProviderPreset(String id, String api, URI baseUrl, List<String> apiKeyEnv, ProviderFlags flags,
    List<ModelRef> models) {

    /** OpenAI Chat Completions compatible. */
    public static final String OPENAI_COMPLETIONS = "openai-completions";

    /**
     * Validates fields and copies lists.
     */
    public ProviderPreset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(flags, "flags");
        apiKeyEnv = List.copyOf(apiKeyEnv);
        models = List.copyOf(models);
    }

    /**
     * Looks a model up by id.
     *
     * @param modelId the model id
     * @return the model, if the preset has it
     */
    public Optional<ModelRef> model(String modelId) {
        return models.stream().filter(m -> m.modelId().equals(modelId)).findFirst();
    }

    /**
     * Returns this preset with another base URL, applied to every model.
     *
     * @param url the new prefix
     * @return the rebased preset
     */
    public ProviderPreset withBaseUrl(URI url) {
        List<ModelRef> rebased = models.stream().map(m -> new ModelRef(m.providerId(), m.modelId(), url,
            m.contextWindow(), m.maxOutputTokens(), m.capabilities())).toList();
        return new ProviderPreset(id, api, url, apiKeyEnv, flags, rebased);
    }
}
