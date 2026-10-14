/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.model.ChatRequest;
import dev.pai.model.ModelRef;
import dev.pai.model.ToolDefinition;

/**
 * Connects the servers of {@code ~/.pai/mcp.json} and {@code <cwd>/.pai/mcp.json} (the latter wins per name) and
 * registers their tools as {@code mcp__<server>__<tool>}, plus the {@code /mcp} command. Loading starts the servers
 * without waiting; the first model request waits at most {@link #STARTUP_WAIT} for starts still in flight, later
 * requests never wait. {@code ${VAR}} in the home file sees the environment and {@code ~/.env}; in the project file
 * also {@code <cwd>/.env}. The pai home is the grandparent of {@link ExtensionContext#configDir()}
 * ({@code ~/.pai/ext/mcp/}).
 */
public final class McpExtension implements Extension {

    /** Extension id. */
    public static final String ID = "mcp";
    /** Client version sent in {@code initialize}. */
    public static final String VERSION = "0.1.0";
    /** How long the first model request (and {@code /mcp reload}) waits for servers still starting. */
    public static final Duration STARTUP_WAIT = Duration.ofSeconds(8);

    private final McpServerManager.TransportFactory factory;
    private final Duration startupWait;
    private volatile Optional<McpServerManager> manager = Optional.empty();

    /**
     * Creates the extension with the real transports; used by {@link java.util.ServiceLoader}.
     */
    public McpExtension() {
        this(McpServerManager::openTransport, STARTUP_WAIT);
    }

    McpExtension(McpServerManager.TransportFactory factory, Duration startupWait) {
        this.factory = factory;
        this.startupWait = startupWait;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onLoad(ExtensionContext ctx) {
        McpServerManager m = new McpServerManager(ctx, factory, new ClientInfo("pai", VERSION));
        manager = Optional.of(m);
        Path project = projectFile(ctx);
        if (Files.isRegularFile(project)) {
            ctx.logger().warn("starting MCP servers from {}; only run pai in directories you trust", project);
        }
        try {
            ctx.registerCommand(new McpCommand(m, () -> load(ctx), startupWait));
            ctx.addHooks(new FirstRequestWait(m, startupWait));
            m.configure(load(ctx));
            m.startAll(Duration.ZERO);
        } catch (RuntimeException e) {
            m.stopAll();
            manager = Optional.empty();
            throw e;
        }
    }

    @Override
    public void onUnload() {
        manager.ifPresent(McpServerManager::stopAll);
        manager = Optional.empty();
    }

    /** Reads both configuration files, each with the variables of its scope. */
    static McpConfig.Loaded load(ExtensionContext ctx) {
        Path homeEnv = Path.of(System.getProperty("user.home")).resolve(".env");
        Path cwdEnv = ctx.cwd().resolve(".env");
        return McpConfig.load(List.of(
            new McpConfig.Source(paiHome(ctx).resolve(McpConfig.FILE_NAME),
                McpConfig.variables(System.getenv(), List.of(homeEnv))),
            new McpConfig.Source(projectFile(ctx), McpConfig.variables(System.getenv(), List.of(cwdEnv, homeEnv)))));
    }

    private static Path paiHome(ExtensionContext ctx) {
        return ctx.configDir().toAbsolutePath().getParent().getParent();
    }

    private static Path projectFile(ExtensionContext ctx) {
        return ctx.cwd().resolve(".pai").resolve(McpConfig.FILE_NAME);
    }

    /**
     * Waits once, before the first model request to a model with tools, for servers that are still starting; the run's
     * cancellation ends the wait. The loop built that request's tool list before this hook runs, so tools registered
     * during the wait are added to it.
     */
    private static final class FirstRequestWait implements AgentHooks {
        private final McpServerManager manager;
        private final Duration wait;
        private final AtomicBoolean done = new AtomicBoolean();

        private FirstRequestWait(McpServerManager manager, Duration wait) {
            this.manager = manager;
            this.wait = wait;
        }

        @Override
        public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
            if (!state.model().capabilities().contains(ModelRef.CapabilityEnum.TOOLS)
                || !done.compareAndSet(false, true)) {
                return request;
            }
            manager.awaitReady(wait, state.cancel());
            Set<String> offered = request.tools().stream().map(ToolDefinition::name).collect(Collectors.toSet());
            List<ToolDefinition> tools = new ArrayList<>(request.tools());
            manager.toolDefinitions().stream().filter(d -> !offered.contains(d.name())).forEach(tools::add);
            return new ChatRequest(request.model(), request.systemPrompt(), request.messages(), tools,
                request.options());
        }
    }
}
