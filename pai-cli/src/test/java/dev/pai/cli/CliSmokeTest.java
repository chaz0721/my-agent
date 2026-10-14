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
        int code = Main.run(full, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
            Map.of("PAI_HOME", home.toString()), false);
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
        EnvConfig cfg = EnvConfig.load(Map.of("A", "from-env"), cwd, home);
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
        assertThat(EventPrinter.summarize("x".repeat(200))).hasSize(80).endsWith("...");
    }

    @Test
    void base_url_never_comes_from_a_lower_layer_than_the_key() throws IOException {
        Files.writeString(cwd.resolve(".env"), "PAI_OPENAI_COMPAT_BASE_URL=https://evil.example\n");
        EnvConfig cfg = EnvConfig.load(Map.of("PAI_OPENAI_COMPAT_API_KEY", "k-env"), cwd, home);
        ProviderSetup.Selected s =
            ProviderSetup.select(CliOptions.parse(new String[] {"--provider", "openai-compat"}), cfg, false);
        assertThat(s.model().baseUrl()).hasToString(ProviderSetup.DEFAULT_OPENAI_BASE_URL);

        Files.writeString(cwd.resolve(".env"),
            "PAI_OPENAI_COMPAT_API_KEY=k-cwd\nPAI_OPENAI_COMPAT_BASE_URL=https://open.bigmodel.cn/api/paas/v4\n");
        EnvConfig sameLayer = EnvConfig.load(Map.of(), cwd, home);
        assertThat(ProviderSetup
            .select(CliOptions.parse(new String[] {"--provider", "openai-compat"}), sameLayer, false).model().baseUrl())
            .hasToString("https://open.bigmodel.cn/api/paas/v4");
        assertThat(
            sameLayer.credentials("PAI_OPENAI_COMPAT_API_KEY", "PAI_OPENAI_COMPAT_BASE_URL").orElseThrow().apiKey())
            .isEqualTo("k-cwd");

        EnvConfig envKeyCwdBoth = EnvConfig.load(Map.of("PAI_OPENAI_COMPAT_API_KEY", "k-env"), cwd, home);
        assertThat(ProviderSetup
            .select(CliOptions.parse(new String[] {"--provider", "openai-compat", "--base-url", "https://cli.example"}),
                envKeyCwdBoth, false)
            .model().baseUrl()).hasToString("https://cli.example");

        EnvConfig cwdKey = EnvConfig.load(Map.of(), cwd, home);
        assertThat(ProviderSetup
            .select(CliOptions.parse(new String[] {"--provider", "openai-compat", "--base-url", "https://cli.example"}),
                cwdKey, false)
            .model().baseUrl()).hasToString("https://cli.example");

        EnvConfig envUrl = EnvConfig.load(Map.of("PAI_OPENAI_COMPAT_BASE_URL", "https://env.example"), cwd, home);
        assertThat(ProviderSetup.select(CliOptions.parse(new String[] {"--provider", "openai-compat"}), envUrl, false)
            .model().baseUrl()).hasToString("https://env.example");
    }

    @Test
    void key_in_home_env_file_ignores_base_url_in_cwd_env_file() throws IOException {
        Files.createDirectories(home);
        Files.writeString(home.resolve(".env"), "PAI_OPENAI_COMPAT_API_KEY=k-home\n");
        Files.writeString(cwd.resolve(".env"), "PAI_OPENAI_COMPAT_BASE_URL=https://evil.example\n");
        EnvConfig cfg = EnvConfig.load(Map.of(), cwd, home);
        ProviderSetup.Selected s =
            ProviderSetup.select(CliOptions.parse(new String[] {"--provider", "openai-compat"}), cfg, false);
        assertThat(s.model().baseUrl()).hasToString(ProviderSetup.DEFAULT_OPENAI_BASE_URL);
    }
}
