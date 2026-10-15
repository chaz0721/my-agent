/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Agent types: the built-in {@code general}, {@code explore} and {@code reviewer} (resource files), then
 * {@code ~/.pai/agents/*.md}, which add types or replace built-in ones of the same name. A file is front matter
 * ({@code name}, {@code description}, {@code tools}, {@code model}, {@code project-context}) and the prompt as body, as
 * Claude Code's agent files are; {@code tools} is {@code *} (all), {@value AgentType#READ_ONLY}, or names separated by
 * commas, optionally in brackets.
 */
final class AgentTypes {

    /** Built-in types, in the order the tool description lists them. */
    static final List<String> BUILTIN = List.of("general", "explore", "reviewer");
    static final String DEFAULT_TYPE = "general";
    private static final String RESOURCE_DIR = "agents/";
    private static final String DELIMITER = "---";
    private static final String SUFFIX = ".md";

    private AgentTypes() {}

    /**
     * Loads the built-in types and those of {@code userDir}; a user file that cannot be read or parsed is skipped with
     * a notice.
     */
    static Map<String, AgentType> load(Path userDir, Consumer<String> notices) {
        Map<String, AgentType> types = new LinkedHashMap<>();
        for (String name : BUILTIN) {
            AgentType t = parse(resource(name + SUFFIX), name, notices);
            types.put(t.name(), t);
        }
        if (!Files.isDirectory(userDir)) {
            return types;
        }
        try (Stream<Path> files = Files.list(userDir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).sorted().toList()) {
                String stem = f.getFileName().toString();
                try {
                    AgentType t = parse(Files.readString(f, StandardCharsets.UTF_8),
                        stem.substring(0, stem.length() - SUFFIX.length()), notices);
                    types.put(t.name(), t);
                } catch (IOException | IllegalArgumentException e) {
                    notices.accept("subagent: skipping agent file " + f.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            notices.accept("subagent: cannot list " + userDir + ": " + e.getMessage());
        }
        return types;
    }

    /** Parses an agent file; the name defaults to the file stem; an empty {@code tools} list (all tools) is noticed. */
    static AgentType parse(String text, String fallbackName, Consumer<String> notices) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        t = t.startsWith("﻿") ? t.substring(1) : t;
        Map<String, String> meta = new LinkedHashMap<>();
        String body = t.strip();
        List<String> lines = t.lines().toList();
        if (!lines.isEmpty() && DELIMITER.equals(lines.get(0).strip())) {
            int close = lines.subList(1, lines.size()).stream().map(String::strip).toList().indexOf(DELIMITER) + 1;
            if (close <= 0) {
                throw new IllegalArgumentException("front matter is not closed");
            }
            for (String line : lines.subList(1, close)) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    meta.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                        unquote(line.substring(colon + 1).strip()));
                }
            }
            body = String.join("\n", lines.subList(close + 1, lines.size())).strip();
        }
        String name = meta.getOrDefault("name", fallbackName).strip();
        if (!name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("invalid agent name: " + name);
        }
        Set<String> tools = tools(meta.get("tools"));
        String raw = meta.getOrDefault("tools", "*").replaceAll("[\\s\\[\\]]", "");
        if (tools.isEmpty() && !"*".equals(raw)) {
            notices.accept("subagent: agent type " + name + " lists no tools, so it gets all tools");
        }
        return new AgentType(name, meta.getOrDefault("description", "").strip(), body, tools,
            !"false".equalsIgnoreCase(meta.getOrDefault("project-context", "true").strip()),
            Optional.ofNullable(meta.get("model")).map(String::strip).filter(m -> !m.isEmpty()));
    }

    private static Set<String> tools(String value) {
        if (value == null) {
            return Set.of();
        }
        String v = value.strip();
        v = v.startsWith("[") && v.endsWith("]") ? v.substring(1, v.length() - 1) : v;
        Set<String> names = Arrays.stream(v.split(",")).map(s -> unquote(s.strip())).filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
        names.remove("*");
        return names;
    }

    private static String unquote(String s) {
        boolean quoted =
            s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"));
        return quoted ? s.substring(1, s.length() - 1) : s;
    }

    private static String resource(String file) {
        try (InputStream in = AgentTypes.class.getResourceAsStream(RESOURCE_DIR + file)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE_DIR + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
