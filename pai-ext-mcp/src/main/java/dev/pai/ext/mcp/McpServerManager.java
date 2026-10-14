/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.tool.Tool;
import dev.pai.model.ToolDefinition;

/**
 * Starts, stops and reloads the configured servers and keeps their tools registered through the
 * {@link ExtensionContext}. Servers connect concurrently on virtual threads. Every start of a server gets a new
 * lifecycle version; results that arrive for an older version (a slow start overtaken by a reload) are discarded and
 * their client closed. {@code notifications/tools/list_changed} re-lists the tools and swaps the registrations. All
 * registry calls happen under this object's lock.
 */
final class McpServerManager {

    /** Notification that the server's tool list changed. */
    static final String TOOLS_CHANGED = "notifications/tools/list_changed";

    /** How long {@link #stopAll()} waits for start threads. */
    static final Duration TERMINATION_WAIT = Duration.ofSeconds(3);

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);

    private final ExtensionContext ctx;
    private final TransportFactory factory;
    private final ClientInfo info;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Server> servers = new LinkedHashMap<>();
    private final List<CompletableFuture<Void>> inFlight = new CopyOnWriteArrayList<>();
    private Map<String, String> invalid = Map.of();

    /**
     * Opens the transport of a server.
     */
    @FunctionalInterface
    interface TransportFactory {
        McpTransport open(McpServerConfig cfg) throws IOException;
    }

    /**
     * What {@code /mcp} shows of one server.
     *
     * @param name server name
     * @param transport {@code stdio}, {@code http} or {@code -} for an invalid configuration
     * @param status lifecycle state
     * @param tools definitions of its registered tools
     * @param error why it failed, empty otherwise
     * @param skipped tools that could not be registered, each with the reason
     */
    record ServerView(String name, String transport, McpStatusEnum status, List<ToolDefinition> tools, String error,
        List<String> skipped) {
    }

    McpServerManager(ExtensionContext ctx, TransportFactory factory, ClientInfo info) {
        this.ctx = ctx;
        this.factory = factory;
        this.info = info;
    }

    /** Opens stdio servers as child processes and http servers over Streamable HTTP. */
    static McpTransport openTransport(McpServerConfig cfg) throws IOException {
        return switch (cfg) {
            case McpServerConfig.Stdio s -> StdioTransportImpl.start(s);
            case McpServerConfig.Http h -> new StreamableHttpTransportImpl(h);
        };
    }

    /** Replaces the configuration: every running server is stopped and the new servers are added, not started. */
    void configure(McpConfig.Loaded cfg) {
        List<McpClient> old = new ArrayList<>();
        synchronized (this) {
            servers.values().forEach(s -> old.addAll(detach(s)));
            servers.clear();
            cfg.servers().forEach(c -> servers.put(c.name(), new Server(c)));
            invalid = cfg.invalid();
        }
        old.forEach(McpClient::close);
    }

    /** Starts every configured server and waits until all are ready or failed, or {@code wait} has passed. */
    void startAll(Duration wait) {
        List<Server> all;
        synchronized (this) {
            all = List.copyOf(servers.values());
        }
        List<CompletableFuture<Void>> started = all.stream().map(this::start).toList();
        if (!wait.isZero()) {
            await(started, wait);
        }
    }

    /** Waits until every start in flight has finished (ready or failed), or {@code wait} has passed. */
    void awaitReady(Duration wait) {
        List<CompletableFuture<Void>> pending = List.copyOf(inFlight);
        if (!pending.isEmpty()) {
            await(pending, wait);
        }
    }

    /**
     * Reloads with a fresh configuration: everything, or only the named server (whose old instance is stopped first).
     *
     * @throws IllegalArgumentException when the named server is neither running nor configured
     */
    void reload(Optional<String> name, McpConfig.Loaded cfg, Duration wait) {
        if (name.isEmpty()) {
            configure(cfg);
            startAll(wait);
            return;
        }
        String n = name.get();
        Optional<McpServerConfig> fresh = cfg.servers().stream().filter(c -> c.name().equals(n)).findFirst();
        List<McpClient> old;
        Optional<Server> next;
        synchronized (this) {
            Server current = servers.get(n);
            if (current == null && fresh.isEmpty()) {
                throw new IllegalArgumentException("no MCP server named " + n);
            }
            old = current == null ? List.of() : detach(current);
            next = fresh.map(Server::new);
            next.ifPresentOrElse(s -> servers.put(n, s), () -> servers.remove(n));
            Map<String, String> inv = new LinkedHashMap<>(invalid);
            inv.remove(n);
            Optional.ofNullable(cfg.invalid().get(n)).ifPresent(r -> inv.put(n, r));
            invalid = Map.copyOf(inv);
        }
        old.forEach(McpClient::close);
        next.ifPresent(s -> await(List.of(start(s)), wait));
    }

    /**
     * Stops every server, including those still starting: pending requests fail, tools are unregistered, processes end.
     * Waits up to {@link #TERMINATION_WAIT} for the start threads to finish.
     */
    void stopAll() {
        List<McpClient> old = new ArrayList<>();
        synchronized (this) {
            servers.values().forEach(s -> old.addAll(detach(s)));
        }
        old.forEach(McpClient::close);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(TERMINATION_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("mcp start threads still running after {} ms", TERMINATION_WAIT.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Returns the definitions of every registered MCP tool. */
    synchronized List<ToolDefinition> toolDefinitions() {
        List<ToolDefinition> out = new ArrayList<>();
        servers.values().forEach(s -> s.tools.forEach(t -> out.add(t.definition())));
        return out;
    }

    /** Returns the state of every server and of every invalid configuration entry. */
    synchronized List<ServerView> servers() {
        List<ServerView> out = new ArrayList<>();
        for (Server s : servers.values()) {
            List<ToolDefinition> defs = s.tools.stream().map(Tool::definition).toList();
            out.add(new ServerView(s.config.name(), s.config.transportName(), s.status, defs, s.error,
                List.copyOf(s.skipped)));
        }
        invalid
            .forEach((n, reason) -> out.add(new ServerView(n, "-", McpStatusEnum.ERROR, List.of(), reason, List.of())));
        return out;
    }

    private CompletableFuture<Void> start(Server s) {
        long v;
        synchronized (this) {
            v = ++s.version;
            s.status = McpStatusEnum.STARTING;
            s.error = "";
        }
        CompletableFuture<Void> f = CompletableFuture.runAsync(() -> connect(s, v), executor);
        inFlight.add(f);
        f.whenComplete((r, e) -> inFlight.remove(f));
        return f;
    }

    private void connect(Server s, long v) {
        Optional<McpClient> client = Optional.empty();
        try {
            McpClient c = new McpClient(factory.open(s.config), s.config.timeout());
            client = Optional.of(c);
            c.onClosed(reason -> lost(s, v, reason));
            c.onNotification(TOOLS_CHANGED, p -> executor.execute(() -> refresh(s, v, c)));
            if (!track(s, v, c)) {
                c.close();
                return;
            }
            InitializeResult init = c.initialize(info);
            List<McpToolDescriptor> tools = init.hasTools() ? c.listTools() : List.of();
            List<McpResource> resources = init.hasResources() ? resources(s, c) : List.of();
            if (!activate(s, v, c, tools, resources)) {
                c.close();
            }
        } catch (IOException | RuntimeException e) {
            client.ifPresent(McpClient::close);
            failed(s, v, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Remembers the client of a start in progress so that a stop can close it at once; false when already stale. */
    private synchronized boolean track(Server s, long v, McpClient c) {
        if (s.version != v) {
            return false;
        }
        s.connecting = Optional.of(c);
        return true;
    }

    private static List<McpResource> resources(Server s, McpClient c) {
        try {
            return c.listResources();
        } catch (McpException e) {
            log.warn("mcp server {} offers resources but listing them failed: {}", s.config.name(), e.getMessage());
            return List.of();
        }
    }

    private synchronized boolean activate(Server s, long v, McpClient c, List<McpToolDescriptor> tools,
        List<McpResource> resources) {
        if (s.version != v || c.isClosed()) {
            log.debug("discarding stale start of mcp server {} (version {})", s.config.name(), v);
            return false;
        }
        s.connecting = Optional.empty();
        s.client = Optional.of(c);
        s.resources = List.copyOf(resources);
        install(s, tools, c);
        s.status = McpStatusEnum.READY;
        log.info("mcp server {} ready with {} tools", s.config.name(), s.tools.size());
        return true;
    }

    private synchronized void failed(Server s, long v, String reason) {
        if (s.version != v) {
            return;
        }
        log.warn("mcp server {} failed: {}", s.config.name(), reason);
        s.connecting = Optional.empty();
        s.status = McpStatusEnum.ERROR;
        s.error = reason;
        uninstall(s);
    }

    private synchronized void lost(Server s, long v, String reason) {
        if (s.version != v || s.status == McpStatusEnum.STOPPED) {
            return;
        }
        log.warn("mcp server {} disconnected: {}", s.config.name(), reason);
        s.status = McpStatusEnum.ERROR;
        s.error = "connection lost: " + reason;
        s.client = Optional.empty();
        uninstall(s);
    }

    /** Re-lists tools after {@code list_changed}; refreshes of one server run one at a time. */
    private void refresh(Server s, long v, McpClient c) {
        s.refreshLock.lock();
        try {
            List<McpToolDescriptor> tools = c.listTools();
            synchronized (this) {
                if (s.version == v && s.status == McpStatusEnum.READY) {
                    install(s, tools, c);
                    log.info("mcp server {} tools changed: now {}", s.config.name(), s.tools.size());
                }
            }
        } catch (McpException e) {
            log.warn("re-listing tools of mcp server {} failed: {}", s.config.name(), e.getMessage());
        } finally {
            s.refreshLock.unlock();
        }
    }

    /** Replaces the server's registered tools. Caller holds the lock. */
    private void install(Server s, List<McpToolDescriptor> tools, McpClient c) {
        uninstall(s);
        List<Tool> wanted = new ArrayList<>();
        tools.forEach(d -> wanted.add(new McpTool(s.config.name(), d, c)));
        if (!s.resources.isEmpty()) {
            wanted.add(new ReadResourceTool(s.config.name(), s.resources, c));
        }
        for (Tool t : wanted) {
            try {
                ctx.registerTool(t);
                s.tools.add(t);
            } catch (IllegalStateException e) {
                log.warn("mcp server {}: skipping tool: {}", s.config.name(), e.getMessage());
                s.skipped.add(t.definition().name() + ": " + e.getMessage());
            }
        }
    }

    /** Caller holds the lock. */
    private void uninstall(Server s) {
        s.tools.forEach(t -> ctx.unregisterTool(t.definition().name()));
        s.tools.clear();
        s.skipped.clear();
    }

    /**
     * Invalidates the current start, unregisters tools and hands back the clients to close (the active one and one
     * still starting). Caller holds the lock and closes them after releasing it.
     */
    private List<McpClient> detach(Server s) {
        s.version++;
        s.status = McpStatusEnum.STOPPED;
        s.error = "";
        uninstall(s);
        List<McpClient> out = new ArrayList<>(2);
        s.client.ifPresent(out::add);
        s.connecting.ifPresent(out::add);
        s.client = Optional.empty();
        s.connecting = Optional.empty();
        return out;
    }

    private static void await(List<CompletableFuture<Void>> started, Duration wait) {
        try {
            CompletableFuture.allOf(started.toArray(new CompletableFuture<?>[0])).get(wait.toMillis(),
                TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("some mcp servers are still starting after {} ms; see /mcp", wait.toMillis());
        } catch (ExecutionException e) {
            log.warn("mcp start failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One configured server; mutable fields are guarded by the manager's lock.
     */
    private static final class Server {
        private final McpServerConfig config;
        private final ReentrantLock refreshLock = new ReentrantLock();
        private final List<Tool> tools = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        private long version;
        private McpStatusEnum status = McpStatusEnum.STOPPED;
        private String error = "";
        private Optional<McpClient> client = Optional.empty();
        private Optional<McpClient> connecting = Optional.empty();
        private List<McpResource> resources = List.of();

        private Server(McpServerConfig config) {
            this.config = config;
        }
    }
}
