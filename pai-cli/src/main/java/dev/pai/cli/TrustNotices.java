/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import dev.pai.core.ext.ExtensionStatus;

/**
 * Startup warnings about code that the working directory brings with it: extension jars from {@code ./.pai/extensions}
 * and MCP servers from {@code ./.pai/mcp.json}. A cloned repository can ship either.
 */
final class TrustNotices {

    /** Id of the MCP extension, which reads {@code ./.pai/mcp.json}. */
    static final String MCP_EXTENSION_ID = "mcp";
    static final String MCP_FILE = "mcp.json";

    private TrustNotices() {}

    static List<String> of(Path cwd, List<ExtensionStatus> statuses) {
        Path projectDir = cwd.resolve(".pai");
        String extDir = projectDir.resolve("extensions").toAbsolutePath().normalize() + File.separator;
        List<String> fromCwd = statuses.stream().filter(ExtensionStatus::enabled)
            .filter(s -> s.source().startsWith(extDir)).map(ExtensionStatus::id).toList();
        List<String> out = new ArrayList<>();
        if (!fromCwd.isEmpty()) {
            out.add("! loaded extensions from ./.pai/extensions (" + fromCwd.stream().collect(Collectors.joining(", "))
                + ") - only run pai in directories you trust");
        }
        boolean mcpLoaded = statuses.stream().anyMatch(s -> s.enabled() && MCP_EXTENSION_ID.equals(s.id()));
        if (mcpLoaded && Files.isRegularFile(projectDir.resolve(MCP_FILE))) {
            out.add("! MCP servers from ./.pai/" + MCP_FILE + " are started - only run pai in directories you trust");
        }
        return out;
    }
}
