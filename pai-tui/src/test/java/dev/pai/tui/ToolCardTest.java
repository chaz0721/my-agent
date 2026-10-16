/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;

/** 9C: tool cards and the shared diff renderer. */
class ToolCardTest {

    private static final Symbols UTF = Symbols.forCharset(StandardCharsets.UTF_8);
    private static final int WIDTH = 80;

    private static List<String> plain(List<AttributedString> lines) {
        return lines.stream().map(AttributedString::toString).toList();
    }

    private static ContentBlock.ToolCall bash(String command) {
        return new ContentBlock.ToolCall("b1", "bash", Json.mapper().createObjectNode().put("command", command));
    }

    private static ToolResult bashResult(String output, int exit) {
        return new ToolResult(List.of(new ContentBlock.Text(output + "\n[exit code: " + exit + "]")), false,
            Optional.of(Json.mapper().createObjectNode().put("exitCode", exit)));
    }

    private static String lines(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append("line ").append(i).append('\n');
        }
        return sb.toString();
    }

    @Test
    void a_long_bash_output_is_folded_with_an_expand_hint() {
        ToolCard card = new ToolCard(3, bash("seq 1 100"));
        card.finish(bashResult(lines(100), 0), Duration.ofMillis(1234));
        List<String> out = plain(card.finished(Theme.DARK, UTF, ToolCard.FOLD_LINES, WIDTH));
        assertThat(out.get(0)).isEqualTo("✓ $ seq 1 100 · exit 0 · 1.2s");
        assertThat(out).hasSize(1 + ToolCard.FOLD_LINES + 1).contains("  line 8").doesNotContain("  line 9");
        assertThat(out.get(out.size() - 1)).isEqualTo("  … (+92 lines, /expand 3)");
        assertThat(plain(card.finished(Theme.DARK, UTF, 0, WIDTH))).hasSize(101).doesNotContain("  [exit code: 0]");
    }

    @Test
    void expanding_shows_the_full_command_and_every_line() {
        String command = "for i in $(seq 1 30); do\n  echo $i\ndone";
        ToolCard card = new ToolCard(1, bash(command));
        card.finish(bashResult(lines(30), 0), Duration.ZERO);
        List<String> header = plain(card.finished(Theme.DARK, UTF, ToolCard.FOLD_LINES, WIDTH));
        assertThat(header.get(0)).startsWith("✓ $ for i in $(seq 1 30); do ...");
        List<String> full = plain(card.expanded(Theme.DARK, UTF, WIDTH));
        assertThat(full).contains("  $ for i in $(seq 1 30); do", "    echo $i", "  done", "  line 30");
        assertThat(full).noneMatch(l -> l.contains("/expand"));
    }

    @Test
    void a_non_zero_exit_is_a_failure_and_shows_more_lines() {
        ToolCard card = new ToolCard(2, bash("mvn verify"));
        card.finish(bashResult(lines(50), 1), Duration.ofMillis(1200));
        List<AttributedString> out = card.finished(Theme.DARK, UTF, ToolCard.FOLD_LINES, WIDTH);
        assertThat(out.get(0).toString()).isEqualTo("✗ $ mvn verify · exit 1 · 1.2s");
        assertThat(out.get(0).toAnsi()).contains("\u001b[91m✗");
        assertThat(out).hasSize(1 + ToolCard.ERROR_FOLD_LINES + 1);
        ToolCard error = new ToolCard(4,
            new ContentBlock.ToolCall("r", "read", Json.mapper().createObjectNode().put("path", "nope.txt")));
        error.finish(ToolResult.error("file not found: nope.txt"), Duration.ZERO);
        assertThat(plain(error.finished(Theme.DARK, UTF, ToolCard.FOLD_LINES, WIDTH)))
            .containsExactly("✗ read nope.txt · 0.0s", "  file not found: nope.txt");
    }

    @Test
    void an_edit_shows_a_numbered_coloured_diff() {
        ContentBlock.ToolCall edit =
            new ContentBlock.ToolCall("e", "edit", Json.mapper().createObjectNode().put("path", "Main.java")
                .put("oldText", "    int x = 1;").put("newText", "    int x = 2;\n    int y = 3;"));
        ToolCard card = new ToolCard(5, edit);
        card.finish(ToolResult.text("edited Main.java:\n1\tclass Main {\n2\t    int x = 2;\n3\t    int y = 3;\n4\t}\n"),
            Duration.ofMillis(300));
        List<AttributedString> out = card.finished(Theme.DARK, UTF, ToolCard.FOLD_LINES, WIDTH);
        assertThat(plain(out)).containsExactly("✓ edit Main.java · 0.3s", "  2 -     int x = 1;",
            "  2 +     int x = 2;", "  3 +     int y = 3;");
        assertThat(out.get(1).toAnsi()).contains("\u001b[91m-     int x = 1;");
        assertThat(out.get(2).toAnsi()).contains("\u001b[32m+     int x = 2;");
    }

    @Test
    void read_shows_no_body_and_write_shows_its_content_as_added_lines() {
        ToolCard read = new ToolCard(6, Events.READ);
        read.finish(ToolResult.text("1\ta\n2\tb\n"), Duration.ofMillis(30));
        assertThat(plain(read.finished(Theme.DARK, UTF, ToolCard.FOLD_LINES, WIDTH)))
            .containsExactly("✓ read src/Main.java · 2 lines · 0.0s");
        ToolCard write = new ToolCard(7, new ContentBlock.ToolCall("w", "write",
            Json.mapper().createObjectNode().put("path", "a.txt").put("content", "x\ny\n")));
        write.finish(ToolResult.text("wrote 4 bytes").withDetails(Json.mapper().createObjectNode().put("bytes", 4)),
            Duration.ZERO);
        assertThat(plain(write.finished(Theme.DARK, UTF, ToolCard.FOLD_LINES, WIDTH)))
            .containsExactly("✓ write a.txt · 4 bytes · 0.0s", "  1 + x", "  2 + y");
    }

    @Test
    void a_sub_agent_card_shows_its_latest_turn_while_running_and_how_it_ended() {
        ContentBlock.ToolCall spawn = new ContentBlock.ToolCall("s", "spawn_agent",
            Json.mapper().createObjectNode().put("task", "find callers").put("agentType", "explore"));
        ToolCard card = new ToolCard(8, spawn);
        card.progress(EventView.turnLine("[sa-1 explore] turn 2: grep effectOf").orElseThrow());
        assertThat(plain(card.running(Theme.DARK, "|", WIDTH))).containsExactly("| spawn_agent explore: find callers",
            "  turn 2: grep effectOf");
        card.finish(ToolResult.text("found 3 callers"), Duration.ofMillis(2000));
        assertThat(plain(card.finished(Theme.DARK, UTF, ToolCard.FOLD_LINES, WIDTH)))
            .containsExactly("✓ spawn_agent explore: find callers · done (2 turns) · 2.0s", "  found 3 callers");
    }

    @Test
    void headers_are_cut_to_the_terminal_width_in_columns() {
        ToolCard card = new ToolCard(9, bash("echo " + "中".repeat(60)));
        card.finish(bashResult("", 0), Duration.ZERO);
        AttributedString header = card.finished(Theme.DARK, UTF, 8, 40).get(0);
        assertThat(header.columnLength()).isLessThanOrEqualTo(40);
        assertThat(header.toString()).startsWith("✓ $ echo 中").endsWith("... · exit 0 · 0.0s");
        assertThat(card.running(Theme.DARK, "|", 40))
            .allSatisfy(l -> assertThat(l.columnLength()).isLessThanOrEqualTo(40));
    }

    @Test
    void the_hitl_unified_diff_and_an_edit_share_one_renderer() {
        String question = "tool edit (write)\n--- /p/Main.java\n+++ /p/Main.java\n@@ -1,3 +1,4 @@\n class Main {\n"
            + "-    int x = 1;\n+    int x = 2;\n+    int y = 3;\n }";
        List<AttributedString> out = DiffView.question(question, Theme.DARK);
        assertThat(plain(out)).containsExactly("tool edit (write)", "--- /p/Main.java", "+++ /p/Main.java",
            "@@ -1,3 +1,4 @@", "1   class Main {", "2 -     int x = 1;", "2 +     int x = 2;", "3 +     int y = 3;",
            "4   }");
        assertThat(out.get(5).toAnsi()).contains("\u001b[91m");
        assertThat(out.get(6).toAnsi()).contains("\u001b[32m");
        assertThat(plain(DiffView.question("tool bash (execute)\n$ rm -rf build", Theme.DARK)))
            .containsExactly("tool bash (execute)", "$ rm -rf build");
        assertThat(DiffView.containsDiff("tool bash (execute)\n$ ls")).isFalse();
    }

    @Test
    void the_expanded_command_shows_control_characters_instead_of_dropping_them() {
        ToolCard card = new ToolCard(1, bash("echo \u001b[2J\u202edone"));
        card.finish(bashResult("x", 0), Duration.ZERO);
        assertThat(plain(card.expanded(Theme.DARK, UTF, WIDTH))).contains("  $ echo \\x1b[2J\\u202edone");
    }

    @Test
    void an_edit_diff_keeps_numbers_through_a_long_common_head_and_tail() {
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            head.append("same ").append(i).append('\n');
        }
        List<DiffView.Line> lines = DiffView.edit(head + "old\n" + head, head + "new\n" + head, 10);
        assertThat(lines).hasSize(4002);
        assertThat(lines.get(2000)).isEqualTo(new DiffView.Line(DiffView.REMOVED, 2010, "old"));
        assertThat(lines.get(2001)).isEqualTo(new DiffView.Line(DiffView.ADDED, 2010, "new"));
        assertThat(lines.get(4001)).isEqualTo(new DiffView.Line(DiffView.CONTEXT, 4010, "same 1999"));
    }

    @Test
    void the_first_line_comes_from_the_numbered_snippet() {
        assertThat(ToolCard.firstLine("edited a:\n9\tx\n10\tnew\n11\ty\n", "new\nmore")).isEqualTo(10);
        assertThat(ToolCard.firstLine("edited a:\n9\tx\n", "absent")).isZero();
        assertThat(ToolCard.firstLine("edited a:\n9\tx\n", "")).isZero();
        assertThat(
            ToolCard.firstLine("edited a:\n1\tclass A {\n2\t    int x = 2;\n3\t    int y;\n", "int x = 2;\n    int y;"))
            .isEqualTo(2);
        assertThat(ToolCard.firstLine("edited a:\n1\tclass A {\n2\t    int x = 2; // c\n", "int x = 2;")).isEqualTo(2);
        assertThat(ToolCard.firstLine("edited a:\n1\t  y = 1;\n2\t  y = 1;\n", "y = 1;")).isZero();
        assertThat(ToolCard.firstLine("edited a:\n1\t  y = 10;\n", "  y = 1;")).isZero();
    }
}
