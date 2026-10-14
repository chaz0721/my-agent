/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import dev.pai.core.ext.CommandContext;
import dev.pai.core.ext.CommandResult;
import dev.pai.core.ext.SlashCommand;
import dev.pai.model.ToolDefinition;

/**
 * {@code /mcp} lists servers; {@code /mcp reload [name]} re-reads the configuration and restarts all servers or one;
 * {@code /mcp tools <name>} lists a server's tools.
 */
final class McpCommand implements SlashCommand {

    private static final int DESCRIPTION_CHARS = 70;

    private final McpServerManager manager;
    private final Supplier<McpConfig.Loaded> config;
    private final Duration wait;

    McpCommand(McpServerManager manager, Supplier<McpConfig.Loaded> config, Duration wait) {
        this.manager = manager;
        this.config = config;
        this.wait = wait;
    }

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String description() {
        return "MCP servers: /mcp, /mcp reload [name], /mcp tools <name>";
    }

    @Override
    public CommandResult run(String args, CommandContext ctx) {
        String[] parts = args.strip().split("\\s+", 2);
        String sub = parts[0];
        Optional<String> arg = parts.length > 1 ? Optional.of(parts[1].strip()) : Optional.empty();
        return switch (sub) {
            case "" -> new CommandResult(list(), false);
            case "reload" -> {
                manager.reload(arg, config.get(), wait);
                yield new CommandResult(list(), false);
            }
            case "tools" -> new CommandResult(
                tools(arg.orElseThrow(() -> new IllegalArgumentException("usage: /mcp tools <name>"))), false);
            default -> throw new IllegalArgumentException("usage: " + description());
        };
    }

    private String list() {
        List<McpServerManager.ServerView> all = manager.servers();
        if (all.isEmpty()) {
            return "no MCP servers (configure ~/.pai/" + McpConfig.FILE_NAME + " or <cwd>/.pai/" + McpConfig.FILE_NAME
                + ")";
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%-16s %-6s %-9s %s", "name", "type", "status", "tools"));
        for (McpServerManager.ServerView s : all) {
            String status = s.status().name().toLowerCase(Locale.ROOT);
            lines.add(String.format(Locale.ROOT, "%-16s %-6s %-9s %d%s", s.name(), s.transport(), status,
                s.tools().size(), s.error().isEmpty() ? "" : "  " + s.error()));
        }
        return String.join("\n", lines);
    }

    private String tools(String name) {
        McpServerManager.ServerView s = manager.servers().stream().filter(v -> v.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no MCP server named " + name));
        if (s.tools().isEmpty()) {
            return name + ": no tools (" + s.status().name().toLowerCase(Locale.ROOT) + ")";
        }
        List<String> lines = new ArrayList<>();
        for (ToolDefinition d : s.tools()) {
            String desc = d.description().lines().findFirst().orElse("");
            if (desc.length() > DESCRIPTION_CHARS) {
                desc = desc.substring(0, DESCRIPTION_CHARS - 3) + "...";
            }
            lines.add(d.name() + "  " + desc);
        }
        return String.join("\n", lines);
    }
}
