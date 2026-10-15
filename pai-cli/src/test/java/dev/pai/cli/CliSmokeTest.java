/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;

class CliSmokeTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private record Result(int exit, String out, String err) {
    }

    private Result run(String input, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] full = Stream.concat(Stream.of("--provider", "fake", "--cwd", cwd.toString()), Stream.of(args))
            .toArray(String[]::new);
        int code = Main.run(full,
            new Main.Io(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                false),
            Map.of("PAI_HOME", home.toString()));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void fake_provider_round_trip_and_quit() {
        Result r = run("hi\n/model\n/nope\n/quit\n");
        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("model=fake/echo", "echo: hi", "fake/echo", "unknown command: /nope");
    }

    @Test
    void session_is_written_as_jsonl_and_resumable() throws IOException {
        assertThat(run("first\n/quit\n").exit()).isZero();
        List<Path> files;
        try (Stream<Path> s = Files.walk(home.resolve("sessions"))) {
            files = s.filter(p -> p.toString().endsWith(".jsonl")).toList();
        }
        assertThat(files).hasSize(1);
        List<String> lines = Files.readAllLines(files.get(0));
        assertThat(lines).hasSize(3);
        for (String line : lines.subList(1, lines.size())) {
            JsonNode n = Json.mapper().readTree(line);
            assertThat(n.has("id") && n.has("parentId") && n.has("type")).isTrue();
        }
        Result resumed = run("second\n/quit\n", "--resume");
        assertThat(resumed.exit()).isZero();
        assertThat(resumed.out()).contains("resumed=2 messages", "echo: second");
        assertThat(Files.readAllLines(files.get(0))).hasSize(5);
    }

    @Test
    void invalid_capability_in_models_json_is_a_readable_usage_error() throws IOException {
        Files.createDirectories(home);
        Files.writeString(home.resolve("models.json"),
            "[{\"providerId\":\"fake\",\"modelId\":\"echo\"," + "\"capabilities\":[\"tools\",\"vision\"]}]");
        Result r = run("/quit\n");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("unknown capability \"vision\"", "tools, image_input, thinking, prompt_cache");
    }

    @Test
    void usage_errors_exit_with_two() {
        assertThat(run("", "--bogus").exit()).isEqualTo(2);
        assertThat(run("", "--resume", "no-such-session").exit()).isEqualTo(2);
        assertThat(run("", "--help").out()).contains("usage: pai");
    }

    @Test
    void env_file_layers_and_parsing() throws IOException {
        Files.writeString(cwd.resolve(".env"), "# comment\n\nA = from-cwd \nB=cwd\nBAD LINE\n");
        Files.createDirectories(home);
        Files.writeString(home.resolve(".env"), "B=home\nC=home\nD=${A}\n");
        EnvConfig cfg = EnvConfig.load(Map.of("A", "from-env"), cwd, home, true);
        assertThat(cfg.get("A")).contains("from-env");
        assertThat(cfg.get("B")).contains("cwd");
        assertThat(cfg.get("C")).contains("home");
        assertThat(cfg.get("D")).contains("${A}");
        assertThat(cfg.get("BAD LINE")).isEmpty();
        assertThat(EnvConfig.parse(cwd.resolve(".env"))).containsEntry("A", "from-cwd");
    }

    @Test
    void cli_options_parse_resume_with_and_without_id() {
        CliOptions a = CliOptions.parse(new String[] {"--resume", "--model", "m"});
        assertThat(a.resume()).isTrue();
        assertThat(a.resumeId()).isEmpty();
        assertThat(a.model()).contains("m");
        CliOptions b = CliOptions.parse(new String[] {"--resume", "abc-1"});
        assertThat(b.resumeId()).contains("abc-1");
    }

    @Test
    void base_url_never_comes_from_a_lower_layer_than_the_key() throws IOException {
        Files.writeString(cwd.resolve(".env"), "PAI_OPENAI_COMPAT_BASE_URL=https://evil.example\n");
        EnvConfig cfg = EnvConfig.load(Map.of("PAI_OPENAI_COMPAT_API_KEY", "k-env"), cwd, home, true);
        ProviderSetup.Selected s =
            ProviderSetup.select(CliOptions.parse(new String[] {"--provider", "openai-compat"}), cfg, false);
        assertThat(s.model().baseUrl()).hasToString(ProviderSetup.DEFAULT_OPENAI_BASE_URL);

        Files.writeString(cwd.resolve(".env"),
            "PAI_OPENAI_COMPAT_API_KEY=k-cwd\nPAI_OPENAI_COMPAT_BASE_URL=https://open.bigmodel.cn/api/paas/v4\n");
        EnvConfig sameLayer = EnvConfig.load(Map.of(), cwd, home, true);
        assertThat(ProviderSetup
            .select(CliOptions.parse(new String[] {"--provider", "openai-compat"}), sameLayer, false).model().baseUrl())
            .hasToString("https://open.bigmodel.cn/api/paas/v4");
        assertThat(
            sameLayer.credentials("PAI_OPENAI_COMPAT_API_KEY", "PAI_OPENAI_COMPAT_BASE_URL").orElseThrow().apiKey())
            .isEqualTo("k-cwd");

        EnvConfig envKeyCwdBoth = EnvConfig.load(Map.of("PAI_OPENAI_COMPAT_API_KEY", "k-env"), cwd, home, true);
        assertThat(ProviderSetup
            .select(CliOptions.parse(new String[] {"--provider", "openai-compat", "--base-url", "https://cli.example"}),
                envKeyCwdBoth, false)
            .model().baseUrl()).hasToString("https://cli.example");

        EnvConfig cwdKey = EnvConfig.load(Map.of(), cwd, home, true);
        assertThat(ProviderSetup
            .select(CliOptions.parse(new String[] {"--provider", "openai-compat", "--base-url", "https://cli.example"}),
                cwdKey, false)
            .model().baseUrl()).hasToString("https://cli.example");

        EnvConfig envUrl = EnvConfig.load(Map.of("PAI_OPENAI_COMPAT_BASE_URL", "https://env.example"), cwd, home, true);
        assertThat(ProviderSetup.select(CliOptions.parse(new String[] {"--provider", "openai-compat"}), envUrl, false)
            .model().baseUrl()).hasToString("https://env.example");
    }

    @Test
    void key_in_home_env_file_ignores_base_url_in_cwd_env_file() throws IOException {
        Files.createDirectories(home);
        Files.writeString(home.resolve(".env"), "PAI_OPENAI_COMPAT_API_KEY=k-home\n");
        Files.writeString(cwd.resolve(".env"), "PAI_OPENAI_COMPAT_BASE_URL=https://evil.example\n");
        EnvConfig cfg = EnvConfig.load(Map.of(), cwd, home, true);
        ProviderSetup.Selected s =
            ProviderSetup.select(CliOptions.parse(new String[] {"--provider", "openai-compat"}), cfg, false);
        assertThat(s.model().baseUrl()).hasToString(ProviderSetup.DEFAULT_OPENAI_BASE_URL);
    }

    private Result start(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] full = Stream.concat(Stream.of("--cwd", cwd.toString()), Stream.of(args)).toArray(String[]::new);
        Map<String, String> all = new java.util.HashMap<>(env);
        all.put("PAI_HOME", home.toString());
        int code = Main.run(full, new Main.Io(new ByteArrayInputStream("/quit\n".getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()), false),
            all);
        return new Result(code, out.toString(StandardCharsets.UTF_8), "");
    }

    private void trust(Path dir) {
        new ProjectTrust(home).record(dir, true);
    }

    @Test
    void cwd_env_file_is_ignored_until_the_project_is_trusted() throws IOException {
        Files.writeString(cwd.resolve(".env"), "PAI_OPENAI_COMPAT_API_KEY=k-cwd\n");
        Result untrusted = start(Map.of(), "--provider", "openai-compat");
        assertThat(untrusted.exit()).isEqualTo(2);

        Result withEnvKey = start(Map.of("PAI_OPENAI_COMPAT_API_KEY", "k-env"), "--provider", "openai-compat");
        assertThat(withEnvKey.out()).contains("model=openai-compat/", ProjectTrust.UNTRUSTED);

        trust(cwd);
        Result trusted = start(Map.of(), "--provider", "openai-compat");
        assertThat(trusted.exit()).isZero();
        assertThat(trusted.out()).contains("! trusted project: loaded ./.env").doesNotContain("not trusted");
    }

    @Test
    void trust_command_records_the_answer() throws IOException {
        Files.createDirectories(cwd.resolve(".pai").resolve("extensions"));
        assertThat(run("/trust\n/quit\n").out()).contains(ProjectTrust.UNTRUSTED, "trusted: ", "restart pai");
        assertThat(run("/trust revoke\n/quit\n").out()).doesNotContain(ProjectTrust.UNTRUSTED)
            .contains("no longer trusted: ");
        assertThat(run("/trust maybe\n/quit\n").out()).contains(ProjectTrust.UNTRUSTED, "usage: /trust");
    }

    @Test
    void no_trust_notices_for_a_plain_directory() {
        Result r = run("/quit\n");
        assertThat(r.out()).doesNotContain("trusted project", "not trusted");
    }

    @Test
    void project_mcp_json_gets_a_notice() throws IOException {
        Files.createDirectories(cwd.resolve(".pai"));
        Files.writeString(cwd.resolve(".pai").resolve("mcp.json"), "{\"mcpServers\":{}}");
        trust(cwd);
        Result r = run("/quit\n");
        assertThat(r.out()).contains("! trusted project: started MCP servers from ./.pai/mcp.json");
    }

    /** The project directory is not cleaned up: the loaded jar stays open (and locked on Windows) until exit. */
    @Test
    void project_extension_jars_get_a_notice(@TempDir(cleanup = CleanupMode.NEVER) Path project, @TempDir Path work)
        throws IOException {
        TestJars.extensionJar(work, project.resolve(".pai").resolve("extensions"), "repoext");
        ByteArrayOutputStream untrusted = new ByteArrayOutputStream();
        Main.run(new String[] {"--provider", "fake", "--cwd", project.toString()},
            new Main.Io(new ByteArrayInputStream("/extensions\n/quit\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(untrusted, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()),
                false),
            Map.of("PAI_HOME", home.toString()));
        assertThat(untrusted.toString(StandardCharsets.UTF_8)).contains(ProjectTrust.UNTRUSTED)
            .doesNotContain("repoext");
        trust(project);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"--provider", "fake", "--cwd", project.toString()},
            new Main.Io(new ByteArrayInputStream("/extensions\n/quit\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()),
                false),
            Map.of("PAI_HOME", home.toString()));
        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
            .contains("! trusted project: loaded extensions from ./.pai/extensions (repoext)", "loaded   repoext");
    }
}
