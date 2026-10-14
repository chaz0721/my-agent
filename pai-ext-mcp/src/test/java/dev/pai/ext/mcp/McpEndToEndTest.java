/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.loop.AgentConfig;
import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentLoop;
import dev.pai.model.CancelToken;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real loop and host with a server that is still starting when the run begins: the first request already offers the
 * MCP tool and the model's call to it succeeds.
 */
@Timeout(30)
class McpEndToEndTest {

    private static final String TOOL = "mcp__slow__echo";
    private static final ModelRef MODEL = new ModelRef(FakeProviderImpl.FAKE_ID, "m", URI.create("http://fake.invalid"),
        100_000, 1000, Set.of(ModelRef.CapabilityEnum.TOOLS));

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    @Test
    void first_request_offers_and_calls_a_tool_that_registers_late() throws IOException {
        Files.writeString(home.resolve(McpConfig.FILE_NAME), "{\"mcpServers\":{\"slow\":{\"command\":\"unused\"}}}");
        InMemoryTransportImpl slow = new InMemoryTransportImpl();
        CountDownLatch gate = new CountDownLatch(1);
        slow.server.initGate = gate;
        ExtensionHost host =
            new ExtensionLoader().install(List.of(new McpExtension(cfg -> slow, Duration.ofSeconds(10))), home, cwd);
        assertThat(host.tools().get(TOOL)).isEmpty();
        List<List<String>> offered = new CopyOnWriteArrayList<>();
        FakeProviderImpl provider = new FakeProviderImpl(FakeProviderImpl.FAKE_ID, req -> {
            offered.add(req.tools().stream().map(ToolDefinition::name).toList());
            if (offered.size() == 1) {
                return FakeProviderImpl.reply(List.of(new ContentBlock.ToolCall("c1", TOOL,
                    Json.mapper().createObjectNode().put("text", "late but here"))), StopReasonEnum.TOOL_USE);
            }
            return FakeProviderImpl.reply(List.of(new ContentBlock.Text("done")), StopReasonEnum.STOP);
        });
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.countDown();
        });
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        AgentLoop loop = new AgentLoop(provider, host.tools(), host.hooks(), AgentConfig.of(MODEL, "sys"), cwd);
        try {
            List<Message> added = loop.run(List.of(), UserMessage.text("go"), events::add, CancelToken.none());
            assertThat(offered.get(0)).contains(TOOL);
            ToolResultMessage result = added.stream().filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast).findFirst().orElseThrow();
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).containsExactly(new ContentBlock.Text("late but here"));
            assertThat(events).noneMatch(AgentEvent.Error.class::isInstance);
        } finally {
            host.unloadAll();
        }
    }
}
