/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.Json;
import dev.pai.model.ModelRef;
import dev.pai.model.http.AnthropicProviderImpl;
import dev.pai.model.preset.ProviderFlags;
import dev.pai.model.preset.ProviderPreset;
import dev.pai.model.preset.ProviderPresets;

/**
 * Models offered by {@code /model}: every model of the provider presets, then the models the user configured in
 * {@code ~/.pai/models.json} (an array of {@code {providerId, modelId, baseUrl?, apiKeyEnv, contextWindow,
 * maxOutputTokens, capabilities[]}}), which replace a preset model with the same reference.
 */
final class ModelCatalog {

    static final String FILE_NAME = "models.json";
    private static final int DEFAULT_WINDOW = 128_000;
    private static final int DEFAULT_MAX_OUTPUT = 8192;

    /**
     * One model and how to reach it.
     *
     * @param model the model, with the base URL to use when nothing overrides it
     * @param apiKeyEnv variables that may hold the key, in order (see {@link ProviderPreset#apiKeyEnv()})
     * @param presetBase whether the base URL is a preset default, which {@code <PREFIX>_BASE_URL} may override
     * @param flags endpoint quirks of an OpenAI-compatible provider
     */
    record Entry(ModelRef model, List<String> apiKeyEnv, boolean presetBase, ProviderFlags flags) {

        String ref() {
            return model.providerId() + "/" + model.modelId();
        }

        String providerId() {
            return model.providerId();
        }
    }

    private final List<Entry> entries;
    private final List<ProviderPreset> presets;

    private ModelCatalog(List<Entry> entries, List<ProviderPreset> presets) {
        this.entries = List.copyOf(entries);
        this.presets = List.copyOf(presets);
    }

    static ModelCatalog empty() {
        return new ModelCatalog(List.of(), List.of());
    }

    /** Reads {@code models.json} alone; a missing file is an empty catalog. */
    static ModelCatalog load(Path file) {
        return load(file, List.of());
    }

    /**
     * Builds the catalog from presets and {@code models.json}; an invalid file is an IllegalArgumentException.
     */
    static ModelCatalog load(Path file, List<ProviderPreset> presets) {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (ProviderPreset p : presets) {
            p.models().forEach(m -> put(out, new Entry(m, p.apiKeyEnv(), true, p.flags())));
        }
        if (Files.isRegularFile(file)) {
            for (JsonNode n : read(file)) {
                put(out, parse(file, n, presets));
            }
        }
        return new ModelCatalog(List.copyOf(out.values()), presets);
    }

    private static void put(Map<String, Entry> out, Entry e) {
        out.put(e.ref(), e);
    }

    private static JsonNode read(Path file) {
        JsonNode root;
        try {
            root = Json.mapper().readTree(file.toFile());
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot parse " + file + ": " + e.getMessage(), e);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException(file + " must be a JSON array of models");
        }
        return root;
    }

    private static Entry parse(Path file, JsonNode n, List<ProviderPreset> presets) {
        String provider = n.path("providerId").asText("");
        String model = n.path("modelId").asText("");
        if (provider.isEmpty() || model.isEmpty()) {
            throw new IllegalArgumentException(file + ": every model needs providerId and modelId");
        }
        Optional<ProviderPreset> preset = ProviderPresets.find(presets, provider);
        Set<ModelRef.CapabilityEnum> caps = capabilities(file, n.path("capabilities"));
        boolean own = n.hasNonNull("baseUrl");
        URI base = own ? URI.create(n.get("baseUrl").asText())
            : preset.map(ProviderPreset::baseUrl).orElseGet(() -> URI.create(defaultBase(provider)));
        String keyEnv = n.path("apiKeyEnv").asText("");
        List<String> keys =
            !keyEnv.isEmpty() ? List.of(keyEnv) : preset.map(ProviderPreset::apiKeyEnv).orElse(List.of());
        ModelRef ref = new ModelRef(provider, model, base, n.path("contextWindow").asInt(DEFAULT_WINDOW),
            n.path("maxOutputTokens").asInt(DEFAULT_MAX_OUTPUT), caps);
        return new Entry(ref, keys, !own && preset.isPresent(),
            preset.map(ProviderPreset::flags).orElse(ProviderFlags.NONE));
    }

    private static String defaultBase(String provider) {
        return "anthropic".equals(provider) ? AnthropicProviderImpl.DEFAULT_BASE_URL
            : ProviderSetup.DEFAULT_OPENAI_BASE_URL;
    }

    /** Parses capability names case-insensitively; a model without the field gets {@code tools}. */
    private static Set<ModelRef.CapabilityEnum> capabilities(Path file, JsonNode names) {
        if (names.isMissingNode() || names.isNull()) {
            return EnumSet.of(ModelRef.CapabilityEnum.TOOLS);
        }
        Set<ModelRef.CapabilityEnum> caps = EnumSet.noneOf(ModelRef.CapabilityEnum.class);
        for (JsonNode c : names) {
            try {
                caps.add(ModelRef.CapabilityEnum.valueOf(c.asText().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(file + ": unknown capability \"" + c.asText() + "\" (expected "
                    + Arrays.stream(ModelRef.CapabilityEnum.values()).map(v -> v.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.joining(", "))
                    + ")", e);
            }
        }
        return caps;
    }

    List<Entry> entries() {
        return entries;
    }

    List<ProviderPreset> presets() {
        return presets;
    }

    Optional<Entry> find(String ref) {
        return entries.stream().filter(e -> e.ref().equals(ref)).findFirst();
    }
}
