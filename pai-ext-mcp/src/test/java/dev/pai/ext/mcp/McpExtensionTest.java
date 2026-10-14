/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.SlashCommand;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The extension inside a real {@link ExtensionHost}, against {@link FakeMcpServerMain} child processes.
 */
@Tag("slow")
@Timeout(90)
class McpExtensionTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private void configure(Path dir, String name, String... extraTools) throws IOException {
        List<String> cmd = StdioTransportImplTest.fakeServerCommand(extraTools);
        ObjectNode root = Json.mapper().createObjectNode();
        ObjectNode server = root.putObject("mcpServers").putObject(name).put("command", cmd.get(0));
        ArrayNode args = server.putArray("args");
        cmd.subList(1, cmd.size()).forEach(args::add);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(McpConfig.FILE_NAME), root.toString());
    }

    @Test
    void servers_from_both_files_become_tools_and_commands_report_them() throws IOException {
        configure(home, "homefake");
        configure(cwd.resolve(".pai"), "projfake", "project_only");
        ExtensionHost host = new ExtensionLoader().install(List.of(new McpExtension()), home, cwd);
        try {
            assertThat(host.tools().get("mcp__homefake__echo")).isPresent();
            assertThat(host.tools().get("mcp__projfake__project_only")).isPresent();
            assertThat(host.tools().sourceOf("mcp__homefake__echo")).contains(McpExtension.ID);
            SlashCommand mcp = host.command("mcp").orElseThrow();
            String list = mcp.run("", null).output();
            assertThat(list).contains("homefake", "projfake", "stdio", "ready");
            assertThat(mcp.run("tools projfake", null).output()).contains("mcp__projfake__project_only");
            assertThat(mcp.run("reload homefake", null).output()).containsPattern("homefake\\s+stdio\\s+ready");
        } finally {
            host.unloadAll();
        }
        assertThat(host.tools().get("mcp__homefake__echo")).isEmpty();
    }
}
