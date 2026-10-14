/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpConfigTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private static final Function<String, Optional<String>> NONE = n -> Optional.empty();

    private static McpConfig.Source source(Path f, Function<String, Optional<String>> vars) {
        return new McpConfig.Source(f, vars);
    }

    private Path write(Path dir, String json) throws IOException {
        Path f = dir.resolve(McpConfig.FILE_NAME);
        Files.writeString(f, json);
        return f;
    }

    @Test
    void project_file_overrides_home_file_per_server() throws IOException {
        Path h = write(home, "{\"mcpServers\":{\"fs\":{\"command\":\"npx\",\"args\":[\"-y\",\"server-fs\"]},"
            + "\"remote\":{\"url\":\"http://a/mcp\"},\"off\":{\"command\":\"x\",\"disabled\":true}}}");
        Path p = write(cwd, "{\"mcpServers\":{\"fs\":{\"type\":\"stdio\",\"command\":\"node\",\"args\":[\"fs.js\"],"
            + "\"env\":{\"A\":\"1\"},\"timeoutMs\":1500}}}");
        McpConfig.Loaded l =
            McpConfig.load(List.of(source(h, NONE), source(p, NONE), source(cwd.resolve("missing.json"), NONE)));
        assertThat(l.invalid()).isEmpty();
        assertThat(l.servers()).extracting(McpServerConfig::name).containsExactly("remote", "fs");
        McpServerConfig.Stdio fs = (McpServerConfig.Stdio)l.servers().get(1);
        assertThat(fs.command()).containsExactly("node", "fs.js");
        assertThat(fs.env()).containsEntry("A", "1");
        assertThat(fs.timeout()).isEqualTo(Duration.ofMillis(1500));
        McpServerConfig.Http remote = (McpServerConfig.Http)l.servers().get(0);
        assertThat(remote.url()).isEqualTo(URI.create("http://a/mcp"));
        assertThat(remote.timeout()).isEqualTo(McpConfig.DEFAULT_TIMEOUT);
    }

    @Test
    void variables_come_from_env_then_cwd_env_then_home_env() throws IOException {
        Files.writeString(cwd.resolve(".env"), "# comment\nTOKEN=\"from-cwd\"\nBOTH=cwd\n");
        Files.writeString(home.resolve(".env"), "HOME_ONLY=h\nBOTH=home\n");
        Function<String, Optional<String>> vars =
            McpConfig.variables(Map.of("ENV_ONLY", "e"), List.of(cwd.resolve(".env"), home.resolve(".env")));
        assertThat(McpConfig.expand("${ENV_ONLY}-${TOKEN}-${HOME_ONLY}-${BOTH}", vars)).isEqualTo("e-from-cwd-h-cwd");
        assertThat(McpConfig.expand("no vars $HOME {X}", vars)).isEqualTo("no vars $HOME {X}");
        assertThatThrownBy(() -> McpConfig.expand("${NOPE}", vars)).hasMessageContaining("NOPE");
    }

    @Test
    void expansion_applies_to_urls_headers_and_args_and_undefined_ones_invalidate_the_server() throws IOException {
        Path f = write(home,
            "{\"mcpServers\":{\"remote\":{\"url\":\"https://${HOST}/mcp\",\"headers\":"
                + "{\"Authorization\":\"Bearer ${TOKEN}\"}},\"bad\":{\"command\":\"${MISSING}\"},"
                + "\"weird\":{\"type\":\"sse\",\"url\":\"http://x\"},\"empty\":{}}}");
        McpConfig.Loaded l = McpConfig
            .load(List.of(source(f, McpConfig.variables(Map.of("HOST", "h.example", "TOKEN", "t"), List.of()))));
        McpServerConfig.Http remote = (McpServerConfig.Http)l.servers().get(0);
        assertThat(remote.url().toString()).isEqualTo("https://h.example/mcp");
        assertThat(remote.headers()).containsEntry("Authorization", "Bearer t");
        assertThat(l.invalid()).containsOnlyKeys("bad", "weird", "empty");
        assertThat(l.invalid().get("bad")).contains("MISSING");
    }

    @Test
    void unparsable_file_is_reported() throws IOException {
        Path f = write(home, "{not json");
        assertThat(McpConfig.load(List.of(source(f, NONE))).invalid()).containsKey(f.toString());
    }

    @Test
    void cwd_env_is_only_visible_to_the_project_file() throws IOException {
        Files.writeString(cwd.resolve(".env"), "REPO_TOKEN=from-repo\n");
        Files.writeString(home.resolve(".env"), "HOME_TOKEN=from-home\n");
        Path homeDir = Files.createDirectories(home.resolve("pai"));
        Path projectDir = Files.createDirectories(cwd.resolve(".pai"));
        Path h = write(homeDir, "{\"mcpServers\":{\"h1\":{\"command\":\"x\",\"args\":[\"${HOME_TOKEN}\"]},"
            + "\"h2\":{\"command\":\"x\",\"args\":[\"${REPO_TOKEN}\"]}}}");
        Path p = write(projectDir,
            "{\"mcpServers\":{\"p1\":{\"command\":\"x\",\"args\":[\"${REPO_TOKEN}\"," + "\"${HOME_TOKEN}\"]}}}");
        Path homeEnv = home.resolve(".env");
        McpConfig.Loaded l = McpConfig.load(List.of(source(h, McpConfig.variables(Map.of(), List.of(homeEnv))),
            source(p, McpConfig.variables(Map.of(), List.of(cwd.resolve(".env"), homeEnv)))));
        assertThat(l.invalid()).containsOnlyKeys("h2");
        assertThat(l.invalid().get("h2")).contains("REPO_TOKEN");
        McpServerConfig.Stdio h1 = (McpServerConfig.Stdio)l.servers().get(0);
        McpServerConfig.Stdio p1 = (McpServerConfig.Stdio)l.servers().get(1);
        assertThat(h1.command()).containsExactly("x", "from-home");
        assertThat(p1.command()).containsExactly("x", "from-repo", "from-home");
    }
}
