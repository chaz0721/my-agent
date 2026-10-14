/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.pai.model.CancelToken;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StdioTransportImplTest {

    /** Command line that runs {@link FakeMcpServerMain} in a child JVM, with explicit class path entries. */
    static List<String> fakeServerCommand(String... extraTools) {
        StringBuilder cp = new StringBuilder(System.getProperty("java.class.path"));
        for (Class<?> c : List.of(FakeMcpServerMain.class, McpClient.class, Json.class, ObjectMapper.class,
            JsonParser.class, JsonInclude.class, Jdk8Module.class, JavaTimeModule.class, LoggerFactory.class)) {
            try {
                cp.append(File.pathSeparator)
                    .append(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()));
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
        List<String> cmd =
            new ArrayList<>(List.of(javaExecutable(), "-cp", cp.toString(), FakeMcpServerMain.class.getName()));
        cmd.addAll(List.of(extraTools));
        return cmd;
    }

    /** The JVM binary with its extension on Windows, so it is started directly rather than through cmd.exe. */
    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    @Test
    void windows_wraps_commands_without_extension_in_cmd() {
        assertThat(StdioTransportImpl.command(List.of("npx", "-y", "pkg"), true)).containsExactly("cmd.exe", "/c",
            "npx", "-y", "pkg");
        assertThat(StdioTransportImpl.command(List.of("C:\\jdk\\bin\\java.exe", "-version"), true))
            .containsExactly("C:\\jdk\\bin\\java.exe", "-version");
        assertThat(StdioTransportImpl.command(List.of("npx"), false)).containsExactly("npx");
    }

    @Test
    @Tag("slow")
    @Timeout(60)
    void talks_to_a_child_process_and_notices_its_death() throws Exception {
        McpServerConfig.Stdio cfg = new McpServerConfig.Stdio("fake", fakeServerCommand("extra"), Map.of(),
            Optional.empty(), Duration.ofSeconds(20));
        StdioTransportImpl t = StdioTransportImpl.start(cfg);
        CompletableFuture<String> closed = new CompletableFuture<>();
        McpClient c = new McpClient(t, Duration.ofSeconds(20));
        c.onClosed(closed::complete);
        assertThat(c.initialize(new ClientInfo("test", "1")).serverName()).isEqualTo("fake");
        assertThat(c.listTools()).extracting(McpToolDescriptor::name).contains("echo", "extra");
        CallToolResult r =
            c.callTool("echo", Json.mapper().createObjectNode().put("text", "héllo"), CancelToken.none());
        assertThat(r.content().get(0).path("text").asText()).isEqualTo("héllo");
        t.process().descendants().forEach(ProcessHandle::destroyForcibly);
        t.process().destroyForcibly();
        assertThat(closed.get(20, TimeUnit.SECONDS)).contains("process");
        assertThat(c.isClosed()).isTrue();
        t.close();
    }

    @Test
    void cmd_wrapping_refuses_metacharacters() {
        for (String bad : List.of("a&b", "x|y", "^", "%PATH%", "<in", ">out", "say \"hi\"")) {
            assertThatThrownBy(() -> StdioTransportImpl.command(List.of("npx", bad), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cmd.exe");
        }
        assertThat(StdioTransportImpl.command(List.of("node.exe", "a&b"), true)).containsExactly("node.exe", "a&b");
        assertThat(StdioTransportImpl.command(List.of("npx", "a&b"), false)).containsExactly("npx", "a&b");
    }

    @Test
    @Tag("slow")
    @Timeout(60)
    void close_also_ends_processes_the_server_started() throws Exception {
        McpServerConfig.Stdio cfg = new McpServerConfig.Stdio("fake", fakeServerCommand(FakeMcpServerMain.GRANDCHILD),
            Map.of(), Optional.empty(), Duration.ofSeconds(20));
        StdioTransportImpl t = StdioTransportImpl.start(cfg);
        McpClient c = new McpClient(t, Duration.ofSeconds(20));
        c.initialize(new ClientInfo("test", "1"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        List<ProcessHandle> kids = List.of();
        while (kids.isEmpty() && System.nanoTime() < deadline) {
            kids = t.process().descendants().toList();
            Thread.onSpinWait();
        }
        assertThat(kids).as("grandchild started").isNotEmpty();
        c.close();
        for (ProcessHandle k : kids) {
            assertThat(k.onExit().get(10, TimeUnit.SECONDS).isAlive()).isFalse();
        }
    }

    @Test
    @Tag("slow")
    @Timeout(60)
    void close_ends_the_process() throws Exception {
        McpServerConfig.Stdio cfg =
            new McpServerConfig.Stdio("fake", fakeServerCommand(), Map.of(), Optional.empty(), Duration.ofSeconds(20));
        StdioTransportImpl t = StdioTransportImpl.start(cfg);
        McpClient c = new McpClient(t, Duration.ofSeconds(20));
        c.initialize(new ClientInfo("test", "1"));
        c.close();
        assertThat(t.process().waitFor(10, TimeUnit.SECONDS)).isTrue();
    }
}
