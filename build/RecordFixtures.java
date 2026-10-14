/* pai-next — Apache-2.0 */

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Records real OpenAI-compatible SSE streams into pai-model test fixtures.
 *
 * <p>Usage (from the repo root): {@code java build/RecordFixtures.java}. Reads PAI_OPENAI_COMPAT_API_KEY and
 * PAI_OPENAI_COMPAT_BASE_URL from the environment, falling back to ./.env. The key is only ever placed in the
 * Authorization header; it is never printed or written. Response lines are scanned for key-shaped strings before
 * being written.
 */
public final class RecordFixtures {

    private static final Path OUT = Path.of("pai-model/src/test/resources/fixtures/openai-compat");
    private static final Pattern SECRET = Pattern.compile("sk-[A-Za-z0-9_-]{16,}");

    private static final String READ_TOOL = """
        {"type":"function","function":{"name":"read","description":"Read a text file relative to the working \
        directory.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"File \
        path"}},"required":["path"]}}}""";

    public static void main(String[] args) throws Exception {
        Map<String, String> env = loadEnv();
        String key = env.get("PAI_OPENAI_COMPAT_API_KEY");
        String base = env.getOrDefault("PAI_OPENAI_COMPAT_BASE_URL", "https://api.deepseek.com");
        if (key == null || key.isBlank()) {
            System.err.println("PAI_OPENAI_COMPAT_API_KEY not found in environment or .env");
            System.exit(2);
        }
        URI endpoint = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
        Files.createDirectories(OUT);
        String filter = args.length > 0 ? args[0] : "";
        record Scenario(String file, String model, String description, String body) {
        }
        List<Scenario> scenarios = List.of(
            new Scenario("text.sse", "deepseek-chat", "plain text reply, no tools",
                body("deepseek-chat", "Reply with exactly: Hello from the fixture.", false, "")),
            new Scenario("tool-calls-parallel.sse", "deepseek-chat",
                "two parallel tool calls with arguments split across chunks",
                body("deepseek-chat", "Use the read tool to read a.txt and b.txt. Call the tool for both files "
                    + "in parallel in a single reply, do not answer in text.", true, "")),
            new Scenario("reasoning.sse", "deepseek-reasoner", "reasoning_content deltas before the answer",
                body("deepseek-reasoner", "What is 17 * 23? Answer with the number only.", false, "")),
            new Scenario("usage-done.sse", "deepseek-chat", "finish_reason length, usage in last chunk, [DONE]",
                body("deepseek-chat", "Count from 1 to 100 separated by spaces.", false, ",\"max_tokens\":8")),
            new Scenario("reasoning-tool-calls.sse", "deepseek-reasoner", "reasoning_content followed by a tool call",
                body("deepseek-reasoner", "Read the file README.md with the read tool.", true, "")));
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        for (Scenario s : scenarios) {
            if (!s.file().contains(filter)) {
                continue;
            }
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(s.body()))
                .build();
            HttpResponse<java.util.stream.Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
            List<String> lines = new ArrayList<>();
            lines.add("# date: " + LocalDate.now() + " (recorded from " + endpoint.getHost() + ")");
            lines.add("# model: " + s.model());
            lines.add("# scenario: " + s.description());
            resp.body().forEach(lines::add);
            if (resp.statusCode() != 200) {
                System.err.println(s.file() + ": HTTP " + resp.statusCode() + " " + String.join("\n", lines));
                continue;
            }
            for (String l : lines) {
                if (l.contains(key) || SECRET.matcher(l).find()) {
                    throw new IllegalStateException("secret-shaped string in response of " + s.file());
                }
            }
            write(OUT.resolve(s.file()), lines);
            System.out.println("recorded " + s.file() + " (" + lines.size() + " lines)");
        }
    }

    private static String body(String model, String prompt, boolean tools, String extra) {
        String toolsJson = tools ? ",\"tools\":[" + READ_TOOL + "]" : "";
        return "{\"model\":\"" + model + "\",\"stream\":true,\"stream_options\":{\"include_usage\":true},"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + "\"}]" + toolsJson + extra + "}";
    }

    private static void write(Path file, List<String> lines) throws IOException {
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static Map<String, String> loadEnv() throws IOException {
        Map<String, String> env = new HashMap<>();
        Path dotenv = Path.of(".env");
        if (Files.exists(dotenv)) {
            for (String line : Files.readAllLines(dotenv, StandardCharsets.UTF_8)) {
                String t = line.strip();
                int eq = t.indexOf('=');
                if (t.isEmpty() || t.startsWith("#") || eq <= 0) {
                    continue;
                }
                env.put(t.substring(0, eq).strip(), t.substring(eq + 1).strip());
            }
        }
        for (String name : List.of("PAI_OPENAI_COMPAT_API_KEY", "PAI_OPENAI_COMPAT_BASE_URL")) {
            String v = System.getenv(name);
            if (v != null && !v.isBlank()) {
                env.put(name, v);
            }
        }
        return env;
    }
}
