/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the built-in slash commands through the plain renderer with the offline echo provider.
 */
class CommandsTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private String run(String input, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] full = Stream.concat(Stream.of("--provider", "fake", "--cwd", cwd.toString()), Stream.of(args))
            .toArray(String[]::new);
        int code = Main.run(full, new Main.Io(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()), false),
            Map.of("PAI_HOME", home.toString()));
        assertThat(code).isZero();
        return out.toString(StandardCharsets.UTF_8);
    }

    private List<JsonNode> sessionLines() throws IOException {
        Path file;
        try (Stream<Path> s = Files.walk(home.resolve("sessions"))) {
            file = s.filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow();
        }
        return Files.readAllLines(file).stream().skip(1).map(l -> {
            try {
                return Json.mapper().readTree(l);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    @Test
    void help_lists_builtin_and_extension_commands() {
        assertThat(run("/help\n")).contains("/help", "/model", "/tree", "/fork", "/label", "/compact", "/clear",
            "/skills", "/quit", "/init", "/memory");
    }

    @Test
    void fork_branches_and_tree_shows_both_branches() throws IOException {
        String out = run("one\ntwo\n/tree\n");
        Matcher m = Pattern.compile("\\* (\\w{6}) user: one").matcher(out);
        assertThat(m.find()).isTrue();
        String first = m.group(1);
        Matcher reply = Pattern.compile("\\* (\\w{6}) assistant: echo: one").matcher(out);
        assertThat(reply.find()).isTrue();
        String out2 = run("/fork " + reply.group(1) + "\nthree\n/label " + first + " start\n/tree\n", "--resume");
        assertThat(out2).contains("echo: three", "+- ", "user: two", "user: three", "[start]", "<- leaf");
        List<JsonNode> lines = sessionLines();
        JsonNode three = lines.stream().filter(n -> n.toString().contains("\"three\"")).findFirst().orElseThrow();
        assertThat(three.path("parentId").asText()).startsWith(reply.group(1));
        assertThat(lines).anyMatch(n -> "label".equals(n.path("type").asText()));
    }

    @Test
    void compact_command_writes_a_compaction_entry_and_context_continues() throws IOException {
        String out = run("a1\na2\na3\na4\n/compact\n/tree\nafter\n");
        assertThat(out).contains("compacted 8 -> ", "compaction (", "echo: after");
        assertThat(sessionLines()).anyMatch(n -> "compaction".equals(n.path("type").asText()));
    }

    @Test
    void small_window_compacts_automatically_and_model_switch_is_recorded() throws IOException {
        Files.createDirectories(home);
        Files.writeString(home.resolve("models.json"),
            "[{\"providerId\":\"fake\",\"modelId\":\"echo\",\"contextWindow\":600,\"maxOutputTokens\":100,"
                + "\"capabilities\":[\"tools\"]},"
                + "{\"providerId\":\"fake\",\"modelId\":\"other\",\"contextWindow\":100000,\"maxOutputTokens\":100}]");
        String big = "x".repeat(900);
        String out = run(big + "\n" + big + "\n" + big + "\n" + big
            + "\n/model\n/model fake/other\n/model nope/x\nhi\n/model\n/tree\n");
        assertThat(out).contains("[context compacted: ", "* fake/echo (window 600, tools)",
            "  fake/other (window 100000, tools)", "* fake/other (window 100000, tools)",
            "model fake/other from the next message on", "not in models.json: nope/x", "model -> fake/other");
        List<JsonNode> lines = sessionLines();
        assertThat(lines).anyMatch(n -> "compaction".equals(n.path("type").asText()));
        assertThat(lines).anyMatch(n -> "model_change".equals(n.path("type").asText()));
        JsonNode last = lines.get(lines.size() - 1);
        assertThat(last.path("message").path("modelId").asText()).isEqualTo("other");
    }

    private Map<String, JsonNode> byId() throws IOException {
        Map<String, JsonNode> m = new HashMap<>();
        sessionLines().forEach(n -> m.put(n.path("id").asText(), n));
        return m;
    }

    private static String id(Map<String, JsonNode> entries, String prefix) {
        return entries.keySet().stream().filter(k -> k.startsWith(prefix)).findFirst().orElseThrow();
    }

    private static List<String> pathTo(Map<String, JsonNode> entries, String leaf) {
        List<String> path = new ArrayList<>();
        for (String c = leaf; c != null && entries.containsKey(c);) {
            path.add(c);
            JsonNode p = entries.get(c).path("parentId");
            c = p.isNull() || p.isMissingNode() ? null : p.asText();
        }
        return path;
    }

    private static String treeId(String out, String line) {
        Matcher m = Pattern.compile("\\* (\\w{6}) " + Pattern.quote(line)).matcher(out);
        assertThat(m.find()).as(line).isTrue();
        return m.group(1);
    }

    @Test
    void compact_after_fork_to_an_equally_long_sibling_stays_on_the_current_branch() throws IOException {
        String out = run("a1\na2\na3\na4\n/tree\n");
        String a3 = treeId(out, "assistant: echo: a3");
        String a4 = treeId(out, "assistant: echo: a4");
        String out2 = run("/fork " + a3 + "\nb4\n/fork " + a4 + "\n/compact\nafter\n", "--resume");
        assertThat(out2).contains("compacted 8 -> 7 messages", "echo: after");
        Map<String, JsonNode> entries = byId();
        JsonNode compaction = entries.values().stream().filter(n -> "compaction".equals(n.path("type").asText()))
            .findFirst().orElseThrow();
        String a4Id = id(entries, a4);
        assertThat(compaction.path("parentId").asText()).isEqualTo(a4Id);
        assertThat(pathTo(entries, a4Id)).contains(compaction.path("firstKeptEntryId").asText());
        JsonNode after =
            entries.values().stream().filter(n -> n.toString().contains("\"after\"")).findFirst().orElseThrow();
        assertThat(after.path("parentId").asText()).isEqualTo(compaction.path("id").asText());
    }

    @Test
    void compact_that_keeps_the_message_count_still_moves_the_leaf() throws IOException {
        String out = run("a1\na2\na3\na4\n/tree\n");
        String u4 = treeId(out, "user: a4");
        String out2 = run("/fork " + u4 + "\n/compact\nnext\n", "--resume");
        assertThat(out2).contains("compacted 7 -> 7 messages").doesNotContain("nothing to compact");
        Map<String, JsonNode> entries = byId();
        JsonNode compaction = entries.values().stream().filter(n -> "compaction".equals(n.path("type").asText()))
            .findFirst().orElseThrow();
        assertThat(compaction.path("parentId").asText()).isEqualTo(id(entries, u4));
        JsonNode next =
            entries.values().stream().filter(n -> n.toString().contains("\"next\"")).findFirst().orElseThrow();
        assertThat(next.path("parentId").asText()).isEqualTo(compaction.path("id").asText());
    }

    @Test
    void skills_and_project_memory() throws IOException {
        Path skill = cwd.resolve(".pai/skills/demo");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: demo\ndescription: demo skill\n---\nDo the demo.");
        Files.writeString(cwd.resolve("PAI.md"), "rules");
        String out = run("/skills\n/skill:demo now\n/memory\n/init\n/clear\n/tree\n");
        assertThat(out).contains("demo  demo skill", "echo: Use skill `demo`. now", "loaded   " + cwd.resolve("PAI.md"),
            "already exists", "new session ", "(empty session)");
    }
}
