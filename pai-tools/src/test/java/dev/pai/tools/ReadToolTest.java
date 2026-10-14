/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;

import static org.assertj.core.api.Assertions.assertThat;

class ReadToolTest {

    @TempDir
    Path dir;

    private final ReadTool tool = new ReadTool();

    @Test
    void numbers_lines_and_reports_absolute_path() throws IOException {
        Files.writeString(dir.resolve("a.txt"), "alpha\nbeta\n");
        ToolResult r = tool.execute(TestContext.args().put("path", "a.txt"), new TestContext(dir));
        assertThat(r.isError()).isFalse();
        assertThat(TestContext.text(r)).isEqualTo("1\talpha\n2\tbeta\n");
        assertThat(r.details().orElseThrow().path("path").asText())
            .isEqualTo(dir.resolve("a.txt").toAbsolutePath().normalize().toString());
        assertThat(tool.effect()).isEqualTo(ToolEffectEnum.READ_ONLY);
        assertThat(tool.concurrencySafe()).isTrue();
    }

    @Test
    void offset_and_limit_page_through_with_hint() throws IOException {
        String body = IntStream.rangeClosed(1, 10).mapToObj(i -> "line" + i).collect(Collectors.joining("\n"));
        Files.writeString(dir.resolve("b.txt"), body);
        ToolResult r = tool.execute(TestContext.args().put("path", "b.txt").put("offset", 3).put("limit", 2),
            new TestContext(dir));
        assertThat(TestContext.text(r)).isEqualTo("3\tline3\n4\tline4\n... 6 more lines; continue with offset=5\n");
        ToolResult past = tool.execute(TestContext.args().put("path", "b.txt").put("offset", 99), new TestContext(dir));
        assertThat(TestContext.text(past)).contains("past the end");
    }

    @Test
    void default_limit_is_2000_lines() throws IOException {
        String body = IntStream.rangeClosed(1, 2005).mapToObj(Integer::toString).collect(Collectors.joining("\n"));
        Files.writeString(dir.resolve("big.txt"), body);
        String out = TestContext.text(tool.execute(TestContext.args().put("path", "big.txt"), new TestContext(dir)));
        assertThat(out).contains("2000\t2000\n").doesNotContain("2001\t")
            .endsWith("5 more lines; continue with offset=2001\n");
    }

    @Test
    void missing_directory_binary_and_oversized_files_are_errors() throws IOException {
        TestContext ctx = new TestContext(dir);
        assertThat(tool.execute(TestContext.args().put("path", "nope.txt"), ctx).isError()).isTrue();
        assertThat(tool.execute(TestContext.args().put("path", "."), ctx).isError()).isTrue();
        Files.write(dir.resolve("bin.dat"), new byte[] {1, 0, 2});
        ToolResult bin = tool.execute(TestContext.args().put("path", "bin.dat"), ctx);
        assertThat(bin.isError()).isTrue();
        assertThat(TestContext.text(bin)).contains("binary");
        Files.write(dir.resolve("huge.txt"), new byte[(int)ReadTool.MAX_BYTES + 1]);
        assertThat(TestContext.text(tool.execute(TestContext.args().put("path", "huge.txt"), ctx))).contains("larger");
        Files.write(dir.resolve("latin1.txt"), "café".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(tool.execute(TestContext.args().put("path", "latin1.txt"), ctx).isError()).isTrue();
    }
}
