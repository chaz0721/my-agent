/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;

/**
 * Connects the servers of {@code ~/.pai/mcp.json} and {@code <cwd>/.pai/mcp.json} (the latter wins per name) and
 * registers their tools as {@code mcp__<server>__<tool>}, plus the {@code /mcp} command. Loading waits at most
 * {@link #STARTUP_WAIT} for the servers; slower ones keep starting in the background. The pai home is the grandparent
 * of {@link ExtensionContext#configDir()} ({@code ~/.pai/ext/mcp/}).
 */
public final class McpExtension implements Extension {

    /** Extension id. */
    public static final String ID = "mcp";
    /** Client version sent in {@code initialize}. */
    public static final String VERSION = "0.1.0";
    /** How long loading waits for servers before continuing. */
    public static final Duration STARTUP_WAIT = Duration.ofSeconds(8);

    private volatile Optional<McpServerManager> manager = Optional.empty();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onLoad(ExtensionContext ctx) {
        McpServerManager m = new McpServerManager(ctx, McpServerManager::openTransport, new ClientInfo("pai", VERSION));
        manager = Optional.of(m);
        Path project = projectFile(ctx);
        if (Files.isRegularFile(project)) {
            ctx.logger().warn("starting MCP servers from {}; only run pai in directories you trust", project);
        }
        m.configure(load(ctx));
        m.startAll(STARTUP_WAIT);
        ctx.registerCommand(new McpCommand(m, () -> load(ctx), STARTUP_WAIT));
    }

    @Override
    public void onUnload() {
        manager.ifPresent(McpServerManager::stopAll);
        manager = Optional.empty();
    }

    /** Reads both configuration files with variables from the environment, {@code <cwd>/.env} and {@code ~/.env}. */
    static McpConfig.Loaded load(ExtensionContext ctx) {
        Path home = Path.of(System.getProperty("user.home"));
        return McpConfig.load(List.of(paiHome(ctx).resolve(McpConfig.FILE_NAME), projectFile(ctx)),
            McpConfig.variables(System.getenv(), ctx.cwd(), home));
    }

    private static Path paiHome(ExtensionContext ctx) {
        return ctx.configDir().toAbsolutePath().getParent().getParent();
    }

    private static Path projectFile(ExtensionContext ctx) {
        return ctx.cwd().resolve(".pai").resolve(McpConfig.FILE_NAME);
    }
}
