/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.CancelToken;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class McpServerManagerTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final RecordingContext ctx = new RecordingContext(Path.of("home"), Path.of("cwd"));
    /** Transports handed out next, per open; when empty a fresh default one is created. */
    private final ConcurrentLinkedDeque<InMemoryTransportImpl> next = new ConcurrentLinkedDeque<>();
    private final List<InMemoryTransportImpl> opened = new CopyOnWriteArrayList<>();
    private final McpServerManager manager = new McpServerManager(ctx, cfg -> {
        InMemoryTransportImpl t = next.isEmpty() ? new InMemoryTransportImpl() : next.removeFirst();
        opened.add(t);
        return t;
    }, new ClientInfo("test", "1"));

    @AfterEach
    void stop() {
        manager.stopAll();
    }

    private static McpConfig.Loaded config(String... names) {
        List<McpServerConfig> servers = Arrays.stream(names).map(n -> (McpServerConfig)new McpServerConfig.Stdio(n,
            List.of("unused"), Map.of(), Optional.empty(), Duration.ofSeconds(5))).toList();
        return new McpConfig.Loaded(servers, Map.of());
    }

    private McpServerManager.ServerView view(String name) {
        return manager.servers().stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow();
    }

    private static void eventually(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met in time").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    @Test
    void started_servers_register_prefixed_tools_that_work() throws Exception {
        manager.configure(config("a", "b"));
        manager.startAll(WAIT);
        assertThat(view("a").status()).isEqualTo(McpStatusEnum.READY);
        assertThat(ctx.tools).containsKeys("mcp__a__echo", "mcp__b__echo", "mcp__a__fail");
        ToolResult r = ctx.tools.get("mcp__a__echo").execute(Json.mapper().createObjectNode().put("text", "hey"),
            new ToolContext() {
                @Override
                public Path cwd() {
                    return Path.of(".");
                }

                @Override
                public CancelToken cancel() {
                    return CancelToken.none();
                }

                @Override
                public void progress(String partial) {
                    // ignored
                }
            });
        assertThat(r.content()).containsExactly(new ContentBlock.Text("hey"));
    }

    @Test
    void a_slow_start_overtaken_by_a_reload_is_discarded() {
        InMemoryTransportImpl slow = new InMemoryTransportImpl();
        CountDownLatch gate = new CountDownLatch(1);
        slow.server.initGate = gate;
        slow.server.tools.add("old_tool");
        next.add(slow);
        manager.configure(config("a"));
        manager.startAll(Duration.ofMillis(100));
        assertThat(view("a").status()).isEqualTo(McpStatusEnum.STARTING);
        InMemoryTransportImpl fast = new InMemoryTransportImpl();
        fast.server.tools.add("new_tool");
        next.add(fast);
        manager.reload(Optional.of("a"), config("a"), WAIT);
        assertThat(view("a").status()).isEqualTo(McpStatusEnum.READY);
        gate.countDown();
        eventually(slow.closed::get);
        assertThat(ctx.tools).containsKey("mcp__a__new_tool").doesNotContainKey("mcp__a__old_tool");
        assertThat(view("a").status()).isEqualTo(McpStatusEnum.READY);
    }

    @Test
    void reload_replaces_the_registered_tools() {
        InMemoryTransportImpl first = new InMemoryTransportImpl();
        first.server.tools.add("v1");
        next.add(first);
        manager.configure(config("a"));
        manager.startAll(WAIT);
        assertThat(ctx.tools).containsKey("mcp__a__v1");
        InMemoryTransportImpl second = new InMemoryTransportImpl();
        second.server.tools.add("v2");
        next.add(second);
        manager.reload(Optional.of("a"), config("a"), WAIT);
        assertThat(ctx.tools).containsKey("mcp__a__v2").doesNotContainKey("mcp__a__v1");
        assertThat(first.closed.get()).isTrue();
        assertThat(ctx.log).containsSubsequence("+mcp__a__v1", "-mcp__a__v1", "+mcp__a__v2");
    }

    @Test
    void list_changed_swaps_the_tools() {
        InMemoryTransportImpl t = new InMemoryTransportImpl();
        next.add(t);
        manager.configure(config("a"));
        manager.startAll(WAIT);
        t.server.tools.remove("fail");
        t.server.tools.add("added");
        t.server.toolsChanged();
        eventually(() -> ctx.tools.containsKey("mcp__a__added"));
        assertThat(ctx.tools).doesNotContainKey("mcp__a__fail");
    }

    @Test
    void a_dead_server_shows_error_and_reload_recovers_it() {
        InMemoryTransportImpl t = new InMemoryTransportImpl();
        next.add(t);
        manager.configure(config("a"));
        manager.startAll(WAIT);
        t.kill();
        eventually(() -> view("a").status() == McpStatusEnum.ERROR);
        assertThat(view("a").error()).contains("killed");
        assertThat(ctx.tools).doesNotContainKey("mcp__a__echo");
        manager.reload(Optional.empty(), config("a"), WAIT);
        assertThat(view("a").status()).isEqualTo(McpStatusEnum.READY);
        assertThat(ctx.tools).containsKey("mcp__a__echo");
    }

    @Test
    void failed_start_and_invalid_entries_are_listed() {
        McpServerManager broken = new McpServerManager(ctx, cfg -> {
            throw new IOException("cannot run " + cfg.name());
        }, new ClientInfo("test", "1"));
        McpConfig.Loaded cfg = new McpConfig.Loaded(config("x").servers(), Map.of("y", "undefined variable TOKEN"));
        broken.configure(cfg);
        broken.startAll(WAIT);
        List<McpServerManager.ServerView> views = broken.servers();
        assertThat(views).extracting(McpServerManager.ServerView::status).containsExactly(McpStatusEnum.ERROR,
            McpStatusEnum.ERROR);
        assertThat(views.get(0).error()).isEqualTo("cannot run x");
        assertThat(views.get(1).transport()).isEqualTo("-");
        assertThat(views.get(1).error()).contains("TOKEN");
        broken.stopAll();
    }

    @Test
    void stop_all_unregisters_everything() {
        manager.configure(config("a"));
        manager.startAll(WAIT);
        manager.stopAll();
        assertThat(ctx.tools).isEmpty();
        assertThat(opened).allMatch(t -> t.closed.get());
        assertThat(view("a").status()).isEqualTo(McpStatusEnum.STOPPED);
    }

    @Test
    void stop_all_closes_a_client_that_is_still_starting() {
        InMemoryTransportImpl slow = new InMemoryTransportImpl();
        slow.server.initGate = new CountDownLatch(1);
        next.add(slow);
        manager.configure(config("a"));
        manager.startAll(Duration.ZERO);
        eventually(() -> slow.server.received.size() > 0);
        manager.stopAll();
        assertThat(slow.closed.get()).as("closed by stopAll itself, not by the late start").isTrue();
        assertThat(ctx.tools).isEmpty();
    }

    @Test
    void await_ready_waits_for_starts_in_flight() {
        InMemoryTransportImpl slow = new InMemoryTransportImpl();
        CountDownLatch gate = new CountDownLatch(1);
        slow.server.initGate = gate;
        next.add(slow);
        manager.configure(config("a"));
        long t0 = System.nanoTime();
        manager.startAll(Duration.ZERO);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(1));
        assertThat(view("a").status()).isEqualTo(McpStatusEnum.STARTING);
        Thread.ofVirtual().start(() -> {
            sleep(200);
            gate.countDown();
        });
        manager.awaitReady(WAIT, CancelToken.none());
        assertThat(view("a").status()).isEqualTo(McpStatusEnum.READY);
        assertThat(manager.toolDefinitions()).extracting(d -> d.name()).contains("mcp__a__echo");
    }

    @Test
    void server_without_tools_capability_only_gets_the_resource_tool() {
        InMemoryTransportImpl t = new InMemoryTransportImpl();
        t.server.toolsCapability = false;
        t.server.resources = true;
        next.add(t);
        manager.configure(config("a"));
        manager.startAll(WAIT);
        assertThat(ctx.tools).containsOnlyKeys("mcp__a__read_resource");
        assertThat(t.server.received).noneMatch(m -> "tools/list".equals(m.path("method").asText()));
    }

    @Test
    void colliding_tool_names_are_listed_as_skipped() {
        InMemoryTransportImpl t = new InMemoryTransportImpl();
        t.server.resources = true;
        t.server.tools.add("read_resource");
        next.add(t);
        manager.configure(config("a"));
        manager.startAll(WAIT);
        assertThat(ctx.tools).containsKey("mcp__a__read_resource");
        assertThat(view("a").skipped()).singleElement().asString().startsWith("mcp__a__read_resource: ");
        McpCommand cmd = new McpCommand(manager, () -> config("a"), WAIT);
        assertThat(cmd.run("tools a", null).output()).contains("skipped mcp__a__read_resource");
    }

    @Test
    void cancellation_ends_the_wait_for_starts() {
        InMemoryTransportImpl slow = new InMemoryTransportImpl();
        slow.server.initGate = new CountDownLatch(1);
        next.add(slow);
        manager.configure(config("a"));
        manager.startAll(Duration.ZERO);
        CancelTokenSource cancel = new CancelTokenSource();
        Thread.ofVirtual().start(() -> {
            sleep(300);
            cancel.cancel();
        });
        long t0 = System.nanoTime();
        manager.awaitReady(Duration.ofSeconds(20), cancel);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(3));
        assertThat(view("a").status()).isEqualTo(McpStatusEnum.STARTING);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
