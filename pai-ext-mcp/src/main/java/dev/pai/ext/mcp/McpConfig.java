/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.Json;

/**
 * Reads {@code mcp.json} files: {@code {"mcpServers": {"<name>": {...}}}} where a server is either {@code {"command",
 * "args"?, "env"?, "cwd"?}} (stdio) or {@code {"url", "headers"?}} (Streamable HTTP), optionally with {@code "type"},
 * {@code "timeoutMs"} and {@code "disabled"}. Later files replace servers of the same name. {@code ${VAR}} in strings
 * is expanded with the variable lookup of the file the server came from; an undefined variable makes that server
 * invalid.
 */
public final class McpConfig {

    /** File name in {@code ~/.pai/} and {@code <cwd>/.pai/}. */
    public static final String FILE_NAME = "mcp.json";
    /** Request timeout of servers that do not set {@code timeoutMs}. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private McpConfig() {}

    /**
     * Valid servers and the reasons the others were rejected.
     *
     * @param servers servers in configuration order
     * @param invalid server name (or file, when the whole file is unreadable) to reason
     */
    public record Loaded(List<McpServerConfig> servers, Map<String, String> invalid) {
        /**
         * Copies the collections.
         */
        public Loaded {
            servers = List.copyOf(servers);
            invalid = Map.copyOf(invalid);
        }
    }

    /**
     * One configuration file and the variables its {@code ${VAR}} references may use.
     *
     * @param file the file; skipped when missing
     * @param vars variable lookup for servers defined in this file
     */
    public record Source(Path file, Function<String, Optional<String>> vars) {
    }

    /**
     * Loads and merges configuration files.
     *
     * @param sources files in increasing priority
     * @return the merged configuration
     */
    public static Loaded load(List<Source> sources) {
        Map<String, JsonNode> raw = new LinkedHashMap<>();
        Map<String, Function<String, Optional<String>>> scope = new LinkedHashMap<>();
        Map<String, String> invalid = new LinkedHashMap<>();
        for (Source src : sources) {
            Path f = src.file();
            if (!Files.isRegularFile(f)) {
                continue;
            }
            try {
                Json.mapper().readTree(f.toFile()).path("mcpServers").fields().forEachRemaining(e -> {
                    raw.remove(e.getKey());
                    invalid.remove(e.getKey());
                    raw.put(e.getKey(), e.getValue());
                    scope.put(e.getKey(), src.vars());
                });
            } catch (IOException e) {
                invalid.put(f.toString(), "cannot parse: " + e.getMessage());
            }
        }
        List<McpServerConfig> servers = new ArrayList<>();
        raw.forEach((name, node) -> {
            if (node.path("disabled").asBoolean(false)) {
                return;
            }
            try {
                servers.add(server(name, node, scope.get(name)));
            } catch (IllegalArgumentException e) {
                invalid.put(name, e.getMessage());
            }
        });
        return new Loaded(servers, invalid);
    }

    /**
     * Returns a variable lookup: the environment, then the given {@code .env} files in order. The files are read on the
     * first lookup that the environment does not answer.
     *
     * @param env process environment
     * @param dotEnvFiles {@code .env} files, highest priority first; missing ones are empty
     * @return the lookup
     */
    public static Function<String, Optional<String>> variables(Map<String, String> env, List<Path> dotEnvFiles) {
        Map<Path, Map<String, String>> files = new ConcurrentHashMap<>();
        List<Path> order = List.copyOf(dotEnvFiles);
        return name -> {
            String direct = env.get(name);
            if (direct != null && !direct.isEmpty()) {
                return Optional.of(direct);
            }
            return order.stream().map(f -> files.computeIfAbsent(f, McpConfig::dotEnv).get(name))
                .filter(v -> v != null && !v.isEmpty()).findFirst();
        };
    }

    /**
     * Expands every {@code ${VAR}}.
     *
     * @param s the text
     * @param vars variable lookup
     * @return the expanded text
     * @throws IllegalArgumentException when a variable is undefined
     */
    public static String expand(String s, Function<String, Optional<String>> vars) {
        Matcher m = VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = vars.apply(m.group(1))
                .orElseThrow(() -> new IllegalArgumentException("undefined variable " + m.group(1)));
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static McpServerConfig server(String name, JsonNode n, Function<String, Optional<String>> vars) {
        String type = n.path("type").asText(n.has("url") ? "http" : "stdio");
        Duration timeout = n.has("timeoutMs") ? Duration.ofMillis(n.get("timeoutMs").asLong()) : DEFAULT_TIMEOUT;
        switch (type) {
            case "stdio" -> {
                if (!n.path("command").isTextual()) {
                    throw new IllegalArgumentException("stdio server needs \"command\"");
                }
                List<String> cmd = new ArrayList<>();
                cmd.add(expand(n.get("command").asText(), vars));
                n.path("args").forEach(a -> cmd.add(expand(a.asText(), vars)));
                Optional<Path> cwd = n.path("cwd").isTextual()
                    ? Optional.of(Path.of(expand(n.get("cwd").asText(), vars))) : Optional.empty();
                return new McpServerConfig.Stdio(name, cmd, strings(n.path("env"), vars), cwd, timeout);
            }
            case "http", "streamable-http", "streamableHttp" -> {
                if (!n.path("url").isTextual()) {
                    throw new IllegalArgumentException("http server needs \"url\"");
                }
                URI url = URI.create(expand(n.get("url").asText(), vars));
                return new McpServerConfig.Http(name, url, strings(n.path("headers"), vars), timeout);
            }
            default -> throw new IllegalArgumentException("unsupported type \"" + type + "\" (stdio or http)");
        }
    }

    private static Map<String, String> strings(JsonNode obj, Function<String, Optional<String>> vars) {
        Map<String, String> out = new LinkedHashMap<>();
        obj.fields().forEachRemaining(e -> out.put(e.getKey(), expand(e.getValue().asText(), vars)));
        return out;
    }

    /** Parses KEY=VALUE lines; blank lines and # comments are skipped; surrounding quotes are removed. */
    static Map<String, String> dotEnv(Path file) {
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
            String v = t.substring(eq + 1).strip();
            boolean quoted =
                v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"));
            out.put(t.substring(0, eq).strip(), quoted ? v.substring(1, v.length() - 1) : v);
        }
        return out;
    }
}
