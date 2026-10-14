/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.tool.ToolResult;

import static org.assertj.core.api.Assertions.assertThat;

class EditToolTest {

    @TempDir
    Path dir;

    private final EditTool tool = new EditTool();

    private ToolResult edit(String oldText, String newText) throws IOException {
        return tool.execute(TestContext.args().put("path", "f.txt").put("oldText", oldText).put("newText", newText),
            new TestContext(dir));
    }

    @Test
    void unique_match_is_replaced_and_context_returned() throws IOException {
        Files.writeString(dir.resolve("f.txt"), "1\n2\n3\n4\nTARGET\n6\n7\n8\n9\n");
        ToolResult r = edit("TARGET", "DONE");
        assertThat(r.isError()).isFalse();
        assertThat(Files.readString(dir.resolve("f.txt"))).isEqualTo("1\n2\n3\n4\nDONE\n6\n7\n8\n9\n");
        assertThat(TestContext.text(r)).isEqualTo("edited f.txt:\n2\t2\n3\t3\n4\t4\n5\tDONE\n6\t6\n7\t7\n8\t8\n");
    }

    @Test
    void zero_matches_is_an_error_and_file_unchanged() throws IOException {
        Files.writeString(dir.resolve("f.txt"), "abc");
        ToolResult r = edit("xyz", "q");
        assertThat(r.isError()).isTrue();
        assertThat(TestContext.text(r)).contains("not found");
        assertThat(r.details().orElseThrow().path("matches").asInt()).isZero();
        assertThat(Files.readString(dir.resolve("f.txt"))).isEqualTo("abc");
    }

    @Test
    void multiple_matches_is_an_error_with_count() throws IOException {
        Files.writeString(dir.resolve("f.txt"), "x x x");
        ToolResult r = edit("x", "y");
        assertThat(r.isError()).isTrue();
        assertThat(TestContext.text(r)).contains("3 times");
        assertThat(Files.readString(dir.resolve("f.txt"))).isEqualTo("x x x");
    }

    @Test
    void lf_old_text_matches_crlf_file() throws IOException {
        Files.writeString(dir.resolve("f.txt"), "a\r\nb\r\nc\r\n");
        ToolResult r = edit("a\nb", "A\nB");
        assertThat(r.isError()).isFalse();
        assertThat(Files.readString(dir.resolve("f.txt"))).isEqualTo("A\r\nB\r\nc\r\n");
    }

    @Test
    void missing_file_and_empty_old_text_are_errors() throws IOException {
        assertThat(edit("a", "b").isError()).isTrue();
        Files.writeString(dir.resolve("f.txt"), "abc");
        assertThat(edit("", "b").isError()).isTrue();
    }
}
