/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.Args;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ToolDefinition;

/**
 * Replaces one exact, unique occurrence of a text in a file.
 */
final class EditTool implements Tool {

    static final int CONTEXT_LINES = 3;
    private static final String CRLF = "\r\n";

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("edit",
            "Replace exactly one occurrence of oldText with newText in a file. oldText must match the file "
                + "verbatim (including whitespace) and must be unique; add surrounding lines to disambiguate.",
            JsonSchema.object().string("path", "File path, relative to the working directory or absolute")
                .string("oldText", "Exact text to replace; must occur exactly once")
                .string("newText", "Replacement text").required("path", "oldText", "newText").build());
    }

    @Override
    public ToolEffectEnum effect() {
        return ToolEffectEnum.WRITE_FS;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) throws IOException {
        Args args = Args.of(arguments);
        String path = args.string("path");
        String oldText = args.string("oldText");
        String newText = args.string("newText");
        Path file = PathResolver.resolve(ctx.cwd(), path);
        if (!Files.isRegularFile(file)) {
            return ToolResult.error("file not found: " + path).withDetails(PathResolver.details(file));
        }
        if (oldText.isEmpty()) {
            return ToolResult.error("oldText must not be empty").withDetails(PathResolver.details(file));
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        int count = count(content, oldText);
        if (count == 0 && content.contains(CRLF) && !oldText.contains(CRLF)) {
            oldText = oldText.replace("\n", CRLF);
            newText = newText.replace("\n", CRLF);
            count = count(content, oldText);
        }
        if (count != 1) {
            String why = count == 0 ? "oldText not found in " + path
                : "oldText matches " + count + " times in " + path + "; include more context to make it unique";
            return ToolResult.error(why).withDetails(PathResolver.details(file).put("matches", count));
        }
        int at = content.indexOf(oldText);
        String updated = content.substring(0, at) + newText + content.substring(at + oldText.length());
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        String snippet = context(updated, at, newText.length());
        return ToolResult.text("edited " + path + ":\n" + snippet).withDetails(PathResolver.details(file));
    }

    private static int count(String content, String needle) {
        int n = 0;
        for (int i = content.indexOf(needle); i >= 0; i = content.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /** Returns the replaced region with {@value #CONTEXT_LINES} lines around it, numbered. */
    private static String context(String text, int start, int length) {
        String[] lines = text.split("\n", -1);
        int firstLine = lineOf(text, start);
        int lastLine = lineOf(text, start + Math.max(0, length - 1));
        int from = Math.max(0, firstLine - CONTEXT_LINES);
        int to = Math.min(lines.length - 1, lastLine + CONTEXT_LINES);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(i + 1).append('\t').append(lines[i].replace("\r", "")).append('\n');
        }
        return sb.toString();
    }

    private static int lineOf(String text, int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
