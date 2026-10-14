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
 * Creates or overwrites a file, creating parent directories.
 */
final class WriteTool implements Tool {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("write",
            "Create a file or overwrite it completely with the given content. Creates parent directories. "
                + "Prefer edit for changes to existing files.",
            JsonSchema.object().string("path", "File path, relative to the working directory or absolute")
                .string("content", "Complete new file content").required("path", "content").build());
    }

    @Override
    public ToolEffectEnum effect() {
        return ToolEffectEnum.WRITE_FS;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) throws IOException {
        Args args = Args.of(arguments);
        String path = args.string("path");
        byte[] bytes = args.string("content").getBytes(StandardCharsets.UTF_8);
        Path file = PathResolver.resolve(ctx.cwd(), path);
        if (Files.isDirectory(file)) {
            return ToolResult.error(path + " is a directory").withDetails(PathResolver.details(file));
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, bytes);
        return ToolResult.text("wrote " + bytes.length + " bytes to " + path)
            .withDetails(PathResolver.details(file).put("bytes", bytes.length));
    }
}
