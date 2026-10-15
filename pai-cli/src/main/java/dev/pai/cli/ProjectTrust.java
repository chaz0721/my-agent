/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.core.ext.ExtensionStatus;
import dev.pai.core.ext.UserInteraction;
import dev.pai.model.Json;

/**
 * The user's answer to "trust this project?" per working directory, kept in {@code ~/.pai/trust.json} as
 * {@code {"projects": {"<real absolute path>": true|false}}}, and the startup notices about it. Only a trusted project
 * gets its {@code ./.env}, {@code ./.pai/mcp.json}, {@code ./.pai/extensions} and absolute {@code PAI.md} imports.
 */
final class ProjectTrust {

    static final String FILE_NAME = "trust.json";
    static final String QUESTION = "This directory has project configuration (./.env or ./.pai/): provider settings, "
        + "MCP servers or extensions that run code. Trust this project and load it?";
    static final String UNTRUSTED =
        "! project not trusted: ./.env, ./.pai/mcp.json and ./.pai/extensions are ignored (/trust to trust it)";
    static final String YES = "y";
    static final List<String> ANSWERS = List.of(YES, "n");
    /** Id of the MCP extension, which reads {@code ./.pai/mcp.json}. */
    static final String MCP_EXTENSION_ID = "mcp";

    private final Path file;

    ProjectTrust(Path paiHome) {
        this.file = paiHome.resolve(FILE_NAME);
    }

    /** Whether the directory brings configuration that needs trust. */
    static boolean hasProjectConfig(Path cwd) {
        return Files.isRegularFile(cwd.resolve(".env")) || Files.isDirectory(cwd.resolve(".pai"));
    }

    /**
     * Decides at startup: a recorded answer wins; otherwise, when there is project configuration and someone can
     * answer, asks. Only an explicit {@code y} or {@code n} is recorded; no answer (end of input, interrupt) means not
     * trusted this time and asks again next time.
     */
    boolean decide(Path cwd, Optional<UserInteraction> ui) {
        JsonNode recorded = read().path("projects").path(key(cwd));
        if (recorded.isBoolean()) {
            return recorded.booleanValue();
        }
        if (!hasProjectConfig(cwd) || ui.isEmpty()) {
            return false;
        }
        String answer = ui.get().ask(QUESTION, ANSWERS);
        if (answer.isEmpty()) {
            return false;
        }
        boolean trusted = YES.equals(answer);
        record(cwd, trusted);
        return trusted;
    }

    void record(Path cwd, boolean trusted) {
        ObjectNode root = read();
        JsonNode projects = root.path("projects");
        (projects instanceof ObjectNode p ? p : root.putObject("projects")).put(key(cwd), trusted);
        try {
            Files.createDirectories(file.getParent());
            Json.mapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    /** A missing or unreadable file holds no answers. */
    private ObjectNode read() {
        try {
            JsonNode n = Files.isRegularFile(file) ? Json.mapper().readTree(file.toFile()) : null;
            return n instanceof ObjectNode o ? o : Json.mapper().createObjectNode();
        } catch (IOException e) {
            return Json.mapper().createObjectNode();
        }
    }

    /**
     * The real path when the directory exists (symlinks resolved, Windows case normalized), else the normalized one.
     */
    static String key(Path cwd) {
        Path abs = cwd.toAbsolutePath().normalize();
        try {
            return abs.toRealPath().toString();
        } catch (IOException e) {
            return abs.toString();
        }
    }

    /** What a trusted project loaded from {@code ./.pai/}, or that an untrusted project's configuration was ignored. */
    static List<String> notices(Path cwd, boolean trusted, List<ExtensionStatus> statuses) {
        if (!trusted) {
            return hasProjectConfig(cwd) ? List.of(UNTRUSTED) : List.of();
        }
        Path projectDir = cwd.resolve(".pai");
        String extDir = projectDir.resolve("extensions").toAbsolutePath().normalize() + File.separator;
        List<String> fromCwd = statuses.stream().filter(ExtensionStatus::enabled)
            .filter(s -> s.source().startsWith(extDir)).map(ExtensionStatus::id).toList();
        List<String> out = new ArrayList<>();
        if (Files.isRegularFile(cwd.resolve(".env"))) {
            out.add("! trusted project: loaded ./.env");
        }
        if (!fromCwd.isEmpty()) {
            out.add("! trusted project: loaded extensions from ./.pai/extensions (" + String.join(", ", fromCwd) + ")");
        }
        boolean mcpLoaded = statuses.stream().anyMatch(s -> s.enabled() && MCP_EXTENSION_ID.equals(s.id()));
        if (mcpLoaded && Files.isRegularFile(projectDir.resolve("mcp.json"))) {
            out.add("! trusted project: started MCP servers from ./.pai/mcp.json");
        }
        return out;
    }
}
