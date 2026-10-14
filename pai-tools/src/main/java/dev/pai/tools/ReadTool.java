/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.Args;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ToolDefinition;

/**
 * Reads a text file and returns it with line numbers.
 */
final class ReadTool implements Tool {

    static final int DEFAULT_LIMIT = 2000;
    static final long MAX_BYTES = 5L * 1024L * 1024L;
    static final int MAX_LINE_CHARS = 2000;
    private static final int BINARY_PROBE_BYTES = 8192;

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("read",
            "Read a text file. Returns lines as '<line number>\\t<content>'. Paths are relative to the working "
                + "directory. Use offset and limit for large files.",
            JsonSchema.object().string("path", "File path, relative to the working directory or absolute")
                .integer("offset", "First line to return, 1-based (default 1)")
                .integer("limit", "Maximum number of lines to return (default " + DEFAULT_LIMIT + ")").required("path")
                .build());
    }

    @Override
    public ToolEffectEnum effect() {
        return ToolEffectEnum.READ_ONLY;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) throws IOException {
        Args args = Args.of(arguments);
        String path = args.string("path");
        int offset = Math.max(1, args.intOr("offset", 1));
        int limit = Math.max(1, args.intOr("limit", DEFAULT_LIMIT));
        Path file = PathResolver.resolve(ctx.cwd(), path);
        if (!Files.exists(file)) {
            return ToolResult.error("file not found: " + path).withDetails(PathResolver.details(file));
        }
        if (Files.isDirectory(file)) {
            return ToolResult.error(path + " is a directory").withDetails(PathResolver.details(file));
        }
        if (Files.size(file) > MAX_BYTES) {
            return ToolResult.error(path + " is larger than " + MAX_BYTES + " bytes")
                .withDetails(PathResolver.details(file));
        }
        if (looksBinary(file)) {
            return ToolResult.error(path + " looks like a binary file").withDetails(PathResolver.details(file));
        }
        List<String> lines;
        try {
            lines = decode(Files.readAllBytes(file)).lines().toList();
        } catch (CharacterCodingException e) {
            return ToolResult.error(path + " is not valid UTF-8 text").withDetails(PathResolver.details(file));
        }
        return ToolResult.text(render(lines, offset, limit)).withDetails(PathResolver.details(file));
    }

    private static String render(List<String> lines, int offset, int limit) {
        if (lines.isEmpty()) {
            return "(empty file)";
        }
        if (offset > lines.size()) {
            return "(offset " + offset + " is past the end of the file; it has " + lines.size() + " lines)";
        }
        int end = Math.min(lines.size(), offset - 1 + limit);
        StringBuilder sb = new StringBuilder();
        for (int i = offset - 1; i < end; i++) {
            String line = lines.get(i);
            if (line.length() > MAX_LINE_CHARS) {
                line = line.substring(0, MAX_LINE_CHARS) + " [line truncated]";
            }
            sb.append(i + 1).append('\t').append(line).append('\n');
        }
        if (end < lines.size()) {
            sb.append("... ").append(lines.size() - end).append(" more lines; continue with offset=").append(end + 1)
                .append('\n');
        }
        return sb.toString();
    }

    private static boolean looksBinary(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] probe = in.readNBytes(BINARY_PROBE_BYTES);
            for (byte b : probe) {
                if (b == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
