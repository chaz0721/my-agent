/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Layered configuration lookup: environment variables, then {@code <cwd>/.env}, then {@code ~/.pai/.env}. Command line
 * values take precedence over all of these and are applied by the caller.
 */
final class EnvConfig {

    private final List<Map<String, String>> layers;

    private EnvConfig(List<Map<String, String>> layers) {
        this.layers = layers;
    }

    static EnvConfig load(Map<String, String> env, Path cwd, Path paiHome) {
        return new EnvConfig(List.of(env, parse(cwd.resolve(".env")), parse(paiHome.resolve(".env"))));
    }

    /**
     * An API key with the base URL of the same configuration layer.
     *
     * @param apiKey the key
     * @param baseUrl the base URL from the layer that holds the key, if it has one
     */
    record Credentials(String apiKey, Optional<String> baseUrl) {
    }

    /**
     * Takes the key from the highest layer that has it. The base URL comes from the environment when set there (the
     * user's own setting); otherwise only from the .env file that supplied the key, so another .env file (for example a
     * cloned repository's) can never redirect the key.
     */
    Optional<Credentials> credentials(String keyName, String baseUrlName) {
        Optional<String> envUrl = value(layers.get(0), baseUrlName);
        for (int i = 0; i < layers.size(); i++) {
            Optional<String> key = value(layers.get(i), keyName);
            if (key.isPresent()) {
                Optional<String> fileUrl = i == 0 ? Optional.empty() : value(layers.get(i), baseUrlName);
                return Optional.of(new Credentials(key.get(), envUrl.or(() -> fileUrl)));
            }
        }
        return Optional.empty();
    }

    /** Index of the {@code <cwd>/.env} layer. */
    static final int CWD_LAYER = 1;

    /**
     * Returns the index of the highest layer that sets a value (0 environment, 1 cwd/.env, 2 ~/.pai/.env).
     *
     * @return the layer index, or -1 when no layer sets it
     */
    int layerOf(String name) {
        for (int i = 0; i < layers.size(); i++) {
            if (value(layers.get(i), name).isPresent()) {
                return i;
            }
        }
        return -1;
    }

    /** Returns a base URL set in the environment layer. */
    Optional<String> environmentValue(String name) {
        return value(layers.get(0), name);
    }

    private static Optional<String> value(Map<String, String> layer, String name) {
        String v = layer.get(name);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.strip());
    }

    Optional<String> get(String key) {
        for (Map<String, String> layer : layers) {
            String v = layer.get(key);
            if (v != null && !v.isBlank()) {
                return Optional.of(v.strip());
            }
        }
        return Optional.empty();
    }

    /** Parses KEY=VALUE lines; blank lines and # comments are ignored, values are trimmed, no expansion. */
    static Map<String, String> parse(Path file) {
        Map<String, String> out = new HashMap<>(16);
        if (!Files.isRegularFile(file)) {
            return out;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        for (String line : lines) {
            String t = line.strip();
            int eq = t.indexOf('=');
            if (t.isEmpty() || t.startsWith("#") || eq <= 0) {
                continue;
            }
            out.put(t.substring(0, eq).strip(), t.substring(eq + 1).strip());
        }
        return out;
    }
}
