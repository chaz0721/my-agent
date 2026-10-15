/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.ToolDefinition;
import dev.pai.model.Usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loading never waits for servers; the first model request does, once, and is offered the tools that arrived.
 */
@Timeout(30)
class McpExtensionStartupTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private static final ModelRef MODEL =
        new ModelRef("fake", "m", URI.create("http://fake.invalid"), 1000, 100, Set.of(ModelRef.CapabilityEnum.TOOLS));

    /** Runs the hooks' prepareRequest as the loop would for a first request with one built-in tool. */
    static ChatRequest firstRequest(AgentHooks hooks) {
        ToolDefinition builtin = new ToolDefinition("read", "r", Json.mapper().createObjectNode());
        ChatRequest req =
            new ChatRequest(MODEL, Optional.of("sys"), List.of(), List.of(builtin), ChatOptions.defaults());
        return hooks.prepareRequest(req, state());
    }

    private static LoopState state() {
        return new LoopState() {
            @Override
            public List<Message> messages() {
                return List.of();
            }

            @Override
            public ModelRef model() {
                return MODEL;
            }

            @Override
            public Usage usageThisRun() {
                return Usage.ZERO;
            }

            @Override
            public int turnIndex() {
                return 0;
            }

            @Override
            public Path cwd() {
                return Path.of(".");
            }

            @Override
            public CancelToken cancel() {
                return CancelToken.none();
            }
        };
    }

    @Test
    void slow_server_does_not_block_loading_and_the_first_request_waits_for_it() throws IOException {
        Files.writeString(home.resolve(McpConfig.FILE_NAME), "{\"mcpServers\":{\"slow\":{\"command\":\"unused\"}}}");
        InMemoryTransportImpl slow = new InMemoryTransportImpl();
        CountDownLatch gate = new CountDownLatch(1);
        slow.server.initGate = gate;
        RecordingContext ctx = new RecordingContext(home, cwd);
        McpExtension ext = new McpExtension(cfg -> slow, Duration.ofSeconds(10));
        long t0 = System.nanoTime();
        ext.onLoad(ctx);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
        assertThat(ctx.log).contains("/mcp");
        assertThat(ctx.tools).isEmpty();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.countDown();
        });
        ToolDefinition builtin = new ToolDefinition("read", "r", Json.mapper().createObjectNode());
        ChatRequest req =
            new ChatRequest(MODEL, Optional.of("sys"), List.of(), List.of(builtin), ChatOptions.defaults());
        ChatRequest first = ctx.hooks.get(0).prepareRequest(req, state());
        assertThat(first).isSameAs(req);
        assertThat(ctx.tools).containsKey("mcp__slow__echo");
        assertThat(ctx.hooks.get(0).prepareRequest(req, state())).isSameAs(req);
        ext.onUnload();
        assertThat(ctx.tools).isEmpty();
    }

    @Test
    void failing_start_after_registration_stops_what_was_started() throws IOException {
        Files.writeString(home.resolve(McpConfig.FILE_NAME), "{\"mcpServers\":{\"a\":{\"command\":\"unused\"}}}");
        RecordingContext ctx = new RecordingContext(home, cwd) {
            @Override
            public void addHooks(AgentHooks h) {
                throw new IllegalStateException("no hooks today");
            }
        };
        McpExtension ext = new McpExtension(cfg -> new InMemoryTransportImpl(), Duration.ofSeconds(1));
        assertThatThrownBy(() -> ext.onLoad(ctx)).isInstanceOf(IllegalStateException.class);
        assertThat(ctx.tools).isEmpty();
    }

    @Test
    void an_untrusted_project_file_is_not_loaded() throws IOException {
        Files.createDirectories(cwd.resolve(".pai"));
        Files.writeString(cwd.resolve(".pai").resolve(McpConfig.FILE_NAME),
            "{\"mcpServers\":{\"project\":{\"command\":\"unused\"}}}");
        Files.writeString(home.resolve(McpConfig.FILE_NAME), "{\"mcpServers\":{\"user\":{\"command\":\"unused\"}}}");
        assertThat(McpExtension.load(new RecordingContext(home, cwd, false)).servers())
            .extracting(McpServerConfig::name).containsExactly("user");
        assertThat(McpExtension.load(new RecordingContext(home, cwd, true)).servers()).extracting(McpServerConfig::name)
            .containsExactlyInAnyOrder("user", "project");
    }
}
