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
     * Takes the key from the highest layer that has it and the base URL only from that same layer, so a lower layer
     * (for example a cloned repository's .env) can never redirect a key from a higher one.
     */
    Optional<Credentials> credentials(String keyName, String baseUrlName) {
        for (Map<String, String> layer : layers) {
            String key = layer.get(keyName);
            if (key != null && !key.isBlank()) {
                String url = layer.get(baseUrlName);
                Optional<String> base = url == null || url.isBlank() ? Optional.empty() : Optional.of(url.strip());
                return Optional.of(new Credentials(key.strip(), base));
            }
        }
        return Optional.empty();
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
