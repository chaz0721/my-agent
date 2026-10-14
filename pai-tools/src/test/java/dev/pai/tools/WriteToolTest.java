/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;

import static org.assertj.core.api.Assertions.assertThat;

class WriteToolTest {

    @TempDir
    Path dir;

    private final WriteTool tool = new WriteTool();

    @Test
    void creates_parents_and_reports_bytes() throws IOException {
        ToolResult r =
            tool.execute(TestContext.args().put("path", "x/y/z.txt").put("content", "hé"), new TestContext(dir));
        assertThat(r.isError()).isFalse();
        assertThat(Files.readString(dir.resolve("x/y/z.txt"))).isEqualTo("hé");
        assertThat(TestContext.text(r)).isEqualTo("wrote 3 bytes to x/y/z.txt");
        assertThat(r.details().orElseThrow().path("bytes").asInt()).isEqualTo(3);
        assertThat(tool.effect()).isEqualTo(ToolEffectEnum.WRITE_FS);
    }

    @Test
    void overwrites_and_refuses_directories() throws IOException {
        Files.writeString(dir.resolve("f.txt"), "old");
        tool.execute(TestContext.args().put("path", "f.txt").put("content", "new"), new TestContext(dir));
        assertThat(Files.readString(dir.resolve("f.txt"))).isEqualTo("new");
        Files.createDirectory(dir.resolve("d"));
        assertThat(
            tool.execute(TestContext.args().put("path", "d").put("content", "x"), new TestContext(dir)).isError())
            .isTrue();
    }
}
