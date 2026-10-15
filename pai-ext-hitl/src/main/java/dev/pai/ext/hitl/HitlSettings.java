/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.Json;

/**
 * Where the mode and the extra write roots come from: the host option {@value #MODE_OPTION} ({@code --approval} or
 * {@code PAI_APPROVAL}), else {@code "mode"} in {@code <configDir>/}{@value #FILE_NAME}, else
 * {@link ApprovalModeEnum#AUTO_READ}. Extra directories writes may go to are {@code "allowDirs"} in the same file.
 *
 * @param mode the approval mode
 * @param allowDirs directories besides the working directory that writes may go to
 * @param problems settings that could not be used, for a notice
 */
record HitlSettings(ApprovalModeEnum mode, List<Path> allowDirs, List<String> problems) {

    static final String MODE_OPTION = "approval";
    static final String FILE_NAME = "config.json";
    static final ApprovalModeEnum DEFAULT_MODE = ApprovalModeEnum.AUTO_READ;

    HitlSettings {
        allowDirs = List.copyOf(allowDirs);
        problems = List.copyOf(problems);
    }

    static HitlSettings load(Path configDir, Optional<String> option) {
        List<String> problems = new ArrayList<>();
        JsonNode file = read(configDir.resolve(FILE_NAME), problems);
        Optional<String> text =
            option.or(() -> file.path("mode").isTextual() ? Optional.of(file.get("mode").asText()) : Optional.empty());
        ApprovalModeEnum mode = DEFAULT_MODE;
        if (text.isPresent()) {
            Optional<ApprovalModeEnum> parsed = ApprovalModeEnum.parse(text.get());
            if (parsed.isEmpty()) {
                problems.add("unknown approval mode '" + text.get() + "', using auto-read");
            }
            mode = parsed.orElse(DEFAULT_MODE);
        }
        List<Path> dirs = new ArrayList<>();
        file.path("allowDirs").forEach(d -> dirs.add(Path.of(d.asText())));
        return new HitlSettings(mode, dirs, problems);
    }

    private static JsonNode read(Path path, List<String> problems) {
        if (Files.isRegularFile(path)) {
            try {
                return Json.mapper().readTree(path.toFile());
            } catch (IOException e) {
                problems.add("cannot read " + path + ": " + e.getMessage());
            }
        }
        return Json.mapper().createObjectNode();
    }
}
