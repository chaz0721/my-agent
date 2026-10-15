/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.Tool;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;

/**
 * Renders what a tool call will do, for the approval prompt: the command line of a {@code command} argument, a unified
 * diff (at most {@value #MAX_DIFF_LINES} lines) for {@code write} ({@code path}, {@code content}) and {@code edit}
 * ({@code path}, {@code oldText}, {@code newText}), and the complete arguments otherwise.
 */
final class CallPreview {

    static final int MAX_DIFF_LINES = 60;
    static final long MAX_DIFF_BYTES = 1_000_000L;
    static final String WRITE_TOOL = "write";
    static final String EDIT_TOOL = "edit";

    private final Path cwd;

    CallPreview(Path cwd) {
        this.cwd = cwd;
    }

    String render(ContentBlock.ToolCall call, Tool tool) {
        JsonNode args = call.arguments();
        String head = "tool " + call.name() + " (" + tool.effect().name().toLowerCase(Locale.ROOT) + ")";
        if (args.path(CommandGuard.COMMAND_ARGUMENT).isTextual()) {
            return head + "\n$ " + args.get(CommandGuard.COMMAND_ARGUMENT).asText();
        }
        boolean write = WRITE_TOOL.equals(call.name()) && args.path("content").isTextual();
        boolean edit =
            EDIT_TOOL.equals(call.name()) && args.path("oldText").isTextual() && args.path("newText").isTextual();
        if ((write || edit) && args.path(PathGuard.PATH_ARGUMENT).isTextual()) {
            return head + "\n" + diff(args, write);
        }
        return head + "\n" + pretty(args);
    }

    private String diff(JsonNode args, boolean write) {
        String shown = args.get(PathGuard.PATH_ARGUMENT).asText();
        Path file;
        try {
            file = cwd.resolve(shown).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return "path " + shown + "\n" + pretty(args);
        }
        String before = "";
        try {
            if (Files.isRegularFile(file)) {
                if (Files.size(file) > MAX_DIFF_BYTES) {
                    return "--- " + file + "\n(file too large to diff)";
                }
                before = Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            before = "";
        }
        Optional<String> after = write ? Optional.of(args.get("content").asText()) : applyEdit(before, args);
        if (after.isEmpty()) {
            return "--- " + file + "\n(oldText not found; the edit will fail)\n-"
                + args.get("oldText").asText().replace("\n", "\n-") + "\n+"
                + args.get("newText").asText().replace("\n", "\n+");
        }
        List<String> lines = new ArrayList<>();
        lines.add("--- " + file + (Files.isRegularFile(file) ? "" : " (new file)"));
        lines.add("+++ " + file);
        lines.addAll(LineDiff.unified(before.lines().toList(), after.get().lines().toList()));
        if (lines.size() > MAX_DIFF_LINES) {
            int more = lines.size() - MAX_DIFF_LINES;
            lines = new ArrayList<>(lines.subList(0, MAX_DIFF_LINES));
            lines.add("... (" + more + " more lines)");
        }
        return String.join("\n", lines);
    }

    /** The file after the edit, empty when {@code oldText} does not occur. */
    private static Optional<String> applyEdit(String before, JsonNode args) {
        String oldText = args.get("oldText").asText();
        int at = oldText.isEmpty() ? -1 : before.indexOf(oldText);
        return at < 0 ? Optional.empty() : Optional
            .of(before.substring(0, at) + args.get("newText").asText() + before.substring(at + oldText.length()));
    }

    private static String pretty(JsonNode args) {
        try {
            return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(args);
        } catch (JsonProcessingException e) {
            return args.toString();
        }
    }
}
