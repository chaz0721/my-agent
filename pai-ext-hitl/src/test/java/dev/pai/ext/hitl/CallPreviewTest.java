/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.tool.ToolEffectEnum;

import static org.assertj.core.api.Assertions.assertThat;

class CallPreviewTest {

    @TempDir
    Path cwd;

    private String render(String tool, String args) {
        return new CallPreview(cwd).render(FakeTool.call(tool, args), new FakeTool(tool, ToolEffectEnum.WRITE_FS));
    }

    @Test
    void edit_shows_a_unified_diff_with_context() throws IOException {
        String body = IntStream.rangeClosed(1, 20).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
        Files.writeString(cwd.resolve("a.txt"), body + "\n");
        String out = render("edit", "{\"path\":\"a.txt\",\"oldText\":\"line 10\",\"newText\":\"LINE TEN\"}");
        assertThat(out)
            .contains("--- " + cwd.resolve("a.txt"), "@@ -7,7 +7,7 @@", "-line 10\n+LINE TEN", " line 7", " line 13")
            .doesNotContain("line 6\n", " line 14");
    }

    @Test
    void write_of_a_new_file_shows_added_lines_and_long_diffs_are_cut() {
        String content = IntStream.rangeClosed(1, 100).mapToObj(i -> "n" + i).collect(Collectors.joining("\\n"));
        String out = render("write", "{\"path\":\"new.txt\",\"content\":\"" + content + "\"}");
        assertThat(out).contains("(new file)", "+n1", "more lines)").doesNotContain("+n100");
        assertThat(out.lines().count()).isLessThanOrEqualTo(CallPreview.MAX_DIFF_LINES + 2);
    }

    @Test
    void edit_whose_old_text_is_missing_says_so() throws IOException {
        Files.writeString(cwd.resolve("b.txt"), "abc\n");
        assertThat(render("edit", "{\"path\":\"b.txt\",\"oldText\":\"zzz\",\"newText\":\"y\"}"))
            .contains("oldText not found", "-zzz", "+y");
    }

    @Test
    void commands_and_other_arguments() {
        assertThat(render("bash", "{\"command\":\"mvn -q verify\"}")).endsWith("$ mvn -q verify");
        assertThat(render("other", "{\"a\":1}")).contains("\"a\" : 1");
    }

    @Test
    void separate_changes_get_separate_hunks() {
        List<String> a = IntStream.rangeClosed(1, 30).mapToObj(i -> "l" + i).toList();
        List<String> b = a.stream().map(s -> "l3".equals(s) || "l25".equals(s) ? s + "!" : s).toList();
        List<String> diff = LineDiff.unified(a, b);
        assertThat(diff.stream().filter(l -> l.startsWith("@@"))).hasSize(2);
        assertThat(LineDiff.unified(a, a)).isEmpty();
    }
}
