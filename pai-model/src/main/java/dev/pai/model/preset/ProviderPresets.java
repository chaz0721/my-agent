/* pai-next — Apache-2.0 */
package dev.pai.model.preset;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.Json;
import dev.pai.model.ModelRef;

/**
 * The provider preset table: the bundled {@value #RESOURCE} plus the user's {@code ~/.pai/providers.json}, both an
 * array of {@code {id, api?, baseUrl, apiKeyEnv[], flags?, models[{id, contextWindow?, maxOutputTokens?,
 * capabilities?}]}}. The tools and image capabilities of a model follow the preset's {@code sendTools} and
 * {@code imageInput} flags; declared capabilities add {@code thinking} and {@code prompt_cache}. A user entry with the
 * id of a bundled one overrides the fields it sets (flags field by field, headers merged, models by id); other user
 * entries are appended.
 */
public final class ProviderPresets {

    /** Class path location of the bundled table. */
    public static final String RESOURCE = "/dev/pai/model/providers.json";
    /** File name of the user's table in {@code ~/.pai/}. */
    public static final String USER_FILE = "providers.json";

    private static final int DEFAULT_WINDOW = 128_000;
    private static final int DEFAULT_MAX_OUTPUT = 8192;

    private ProviderPresets() {}

    /**
     * Returns the bundled presets.
     *
     * @return presets in table order
     */
    public static List<ProviderPreset> builtin() {
        try (InputStream in = Objects.requireNonNull(ProviderPresets.class.getResourceAsStream(RESOURCE), RESOURCE)) {
            return merge(List.of(), Json.mapper().readTree(in), RESOURCE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }

    /**
     * Merges a user table over presets; a missing file changes nothing.
     *
     * @param builtin the presets to start from
     * @param userFile the user's table
     * @return merged presets: overridden ones in place, new ones appended
     * @throws IllegalArgumentException when the file is not a valid table
     */
    public static List<ProviderPreset> merge(List<ProviderPreset> builtin, Path userFile) {
        if (!Files.isRegularFile(userFile)) {
            return builtin;
        }
        try {
            return merge(builtin, Json.mapper().readTree(userFile.toFile()), userFile.toString());
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot parse " + userFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Finds a preset by id.
     *
     * @param presets the table
     * @param id provider id
     * @return the preset, if present
     */
    public static Optional<ProviderPreset> find(List<ProviderPreset> presets, String id) {
        return presets.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    private static List<ProviderPreset> merge(List<ProviderPreset> base, JsonNode root, String file) {
        if (!root.isArray()) {
            throw new IllegalArgumentException(file + " must be a JSON array of providers");
        }
        Map<String, ProviderPreset> out = new LinkedHashMap<>();
        base.forEach(p -> out.put(p.id(), p));
        for (JsonNode n : root) {
            String id = n.path("id").asText("");
            if (id.isEmpty()) {
                throw new IllegalArgumentException(file + ": every provider needs an id");
            }
            out.put(id, parse(n, Optional.ofNullable(out.get(id)), file));
        }
        return List.copyOf(out.values());
    }

    private static ProviderPreset parse(JsonNode n, Optional<ProviderPreset> base, String file) {
        String id = n.get("id").asText();
        String api = n.hasNonNull("api") ? n.get("api").asText()
            : base.map(ProviderPreset::api).orElse(ProviderPreset.OPENAI_COMPLETIONS);
        Optional<URI> url = n.hasNonNull("baseUrl") ? Optional.of(URI.create(n.get("baseUrl").asText()))
            : base.map(ProviderPreset::baseUrl);
        if (url.isEmpty()) {
            throw new IllegalArgumentException(file + ": provider " + id + " needs a baseUrl");
        }
        List<String> keys =
            n.has("apiKeyEnv") ? strings(n.get("apiKeyEnv")) : base.map(ProviderPreset::apiKeyEnv).orElse(List.of());
        ProviderFlags flags = flags(n.path("flags"), base.map(ProviderPreset::flags).orElse(ProviderFlags.NONE));
        Map<String, ModelRef> models = new LinkedHashMap<>();
        base.ifPresent(b -> b.models().forEach(m -> models.put(m.modelId(), m)));
        for (JsonNode m : n.path("models")) {
            String modelId = m.path("id").asText("");
            if (modelId.isEmpty()) {
                throw new IllegalArgumentException(file + ": every model of " + id + " needs an id");
            }
            models.put(modelId, new ModelRef(id, modelId, url.get(), m.path("contextWindow").asInt(DEFAULT_WINDOW),
                m.path("maxOutputTokens").asInt(DEFAULT_MAX_OUTPUT), declared(m.path("capabilities"), file)));
        }
        List<ModelRef> flagged = models.values().stream().map(m -> new ModelRef(id, m.modelId(), url.get(),
            m.contextWindow(), m.maxOutputTokens(), capabilities(m.capabilities(), flags))).toList();
        return new ProviderPreset(id, api, url.get(), keys, flags, flagged);
    }

    private static ProviderFlags flags(JsonNode n, ProviderFlags base) {
        Map<String, String> headers = new HashMap<>(base.extraHeaders());
        n.path("extraHeaders").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asText()));
        return new ProviderFlags(n.path("forceHttp1").asBoolean(base.forceHttp1()),
            n.path("echoReasoning").asBoolean(base.echoReasoning()), n.path("imageInput").asBoolean(base.imageInput()),
            n.path("sendTools").asBoolean(base.sendTools()), headers);
    }

    private static Set<ModelRef.CapabilityEnum> declared(JsonNode names, String file) {
        Set<ModelRef.CapabilityEnum> caps = EnumSet.noneOf(ModelRef.CapabilityEnum.class);
        for (JsonNode c : names) {
            try {
                caps.add(ModelRef.CapabilityEnum.valueOf(c.asText().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(file + ": unknown capability \"" + c.asText() + "\"", e);
            }
        }
        return caps;
    }

    /** Tools and image input follow the flags; the other capabilities are kept as declared. */
    private static Set<ModelRef.CapabilityEnum> capabilities(Set<ModelRef.CapabilityEnum> declared, ProviderFlags f) {
        Set<ModelRef.CapabilityEnum> caps = EnumSet.noneOf(ModelRef.CapabilityEnum.class);
        caps.addAll(declared);
        caps.remove(ModelRef.CapabilityEnum.TOOLS);
        caps.remove(ModelRef.CapabilityEnum.IMAGE_INPUT);
        if (f.sendTools()) {
            caps.add(ModelRef.CapabilityEnum.TOOLS);
        }
        if (f.imageInput()) {
            caps.add(ModelRef.CapabilityEnum.IMAGE_INPUT);
        }
        return caps;
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isTextual()) {
            out.add(n.asText());
        }
        n.forEach(v -> out.add(v.asText()));
        return out;
    }
}
