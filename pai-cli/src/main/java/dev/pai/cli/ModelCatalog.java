/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.Json;
import dev.pai.model.ModelRef;

/**
 * Models the user configured in {@code ~/.pai/models.json}: an array of {@code {providerId, modelId, baseUrl?,
 * apiKeyEnv, contextWindow, maxOutputTokens, capabilities[]}}.
 */
final class ModelCatalog {

    static final String FILE_NAME = "models.json";
    private static final int DEFAULT_WINDOW = 128_000;
    private static final int DEFAULT_MAX_OUTPUT = 8192;

    /**
     * One configured model.
     *
     * @param providerId provider id: anthropic, fake, or any id served by an OpenAI-compatible endpoint
     * @param modelId model id
     * @param baseUrl API base URL; the provider default when empty
     * @param apiKeyEnv name of the variable holding the key (environment, ./.env or ~/.pai/.env)
     * @param contextWindow context window in tokens
     * @param maxOutputTokens output limit
     * @param capabilities capabilities
     */
    record Entry(String providerId, String modelId, Optional<String> baseUrl, String apiKeyEnv, int contextWindow,
        int maxOutputTokens, Set<ModelRef.CapabilityEnum> capabilities) {

        String ref() {
            return providerId + "/" + modelId;
        }

        ModelRef toModel(URI defaultBase) {
            return new ModelRef(providerId, modelId, baseUrl.map(URI::create).orElse(defaultBase), contextWindow,
                maxOutputTokens, capabilities);
        }
    }

    private final List<Entry> entries;

    private ModelCatalog(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    static ModelCatalog empty() {
        return new ModelCatalog(List.of());
    }

    /** Reads the catalog; a missing file is an empty catalog, an invalid one an IllegalArgumentException. */
    static ModelCatalog load(Path file) {
        if (!Files.isRegularFile(file)) {
            return empty();
        }
        JsonNode root;
        try {
            root = Json.mapper().readTree(file.toFile());
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot parse " + file + ": " + e.getMessage(), e);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException(file + " must be a JSON array of models");
        }
        List<Entry> out = new ArrayList<>();
        for (JsonNode n : root) {
            String provider = n.path("providerId").asText("");
            String model = n.path("modelId").asText("");
            if (provider.isEmpty() || model.isEmpty()) {
                throw new IllegalArgumentException(file + ": every model needs providerId and modelId");
            }
            Set<ModelRef.CapabilityEnum> caps = EnumSet.noneOf(ModelRef.CapabilityEnum.class);
            for (JsonNode c : n.path("capabilities")) {
                caps.add(ModelRef.CapabilityEnum.valueOf(c.asText().toUpperCase(Locale.ROOT)));
            }
            Optional<String> base = n.hasNonNull("baseUrl") ? Optional.of(n.get("baseUrl").asText()) : Optional.empty();
            out.add(new Entry(provider, model, base, n.path("apiKeyEnv").asText(""),
                n.path("contextWindow").asInt(DEFAULT_WINDOW), n.path("maxOutputTokens").asInt(DEFAULT_MAX_OUTPUT),
                caps));
        }
        return new ModelCatalog(out);
    }

    List<Entry> entries() {
        return entries;
    }

    Optional<Entry> find(String ref) {
        return entries.stream().filter(e -> e.ref().equals(ref)).findFirst();
    }
}
