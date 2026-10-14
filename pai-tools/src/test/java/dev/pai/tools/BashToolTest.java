/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
class BashToolTest {

    @TempDir
    Path dir;

    private final BashTool tool = new BashTool();

    @Test
    void runs_in_cwd_and_merges_stderr_with_exit_code() throws Exception {
        Files.writeString(dir.resolve("marker.txt"), "x");
        ToolResult r =
            tool.execute(TestContext.args().put("command", "ls && echo oops 1>&2 && exit 3"), new TestContext(dir));
        String out = TestContext.text(r);
        assertThat(out).contains("marker.txt", "oops").endsWith("[exit code: 3]");
        assertThat(r.isError()).isFalse();
        assertThat(r.details().orElseThrow().path("exitCode").asInt()).isEqualTo(3);
        assertThat(tool.effect()).isEqualTo(ToolEffectEnum.EXECUTE);
    }

    @Test
    @Timeout(30)
    void timeout_kills_the_process() throws Exception {
        long start = System.nanoTime();
        ToolResult r =
            tool.execute(TestContext.args().put("command", "sleep 20").put("timeoutSeconds", 1), new TestContext(dir));
        assertThat(r.isError()).isTrue();
        assertThat(TestContext.text(r)).contains("timed out after 1s");
        assertThat(System.nanoTime() - start).isLessThan(15_000_000_000L);
    }

    @Test
    @Timeout(30)
    void cancel_kills_the_process() throws Exception {
        TestContext ctx = new TestContext(dir);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ctx.cancel.cancel();
        });
        ToolResult r = tool.execute(TestContext.args().put("command", "sleep 20"), ctx);
        assertThat(TestContext.text(r)).contains("cancelled");
    }

    @Test
    void large_output_is_truncated_and_marked() throws IOException, InterruptedException {
        ToolResult r = tool.execute(TestContext.args().put("command", "head -c 60000 /dev/zero | tr '\\0' a"),
            new TestContext(dir));
        String out = TestContext.text(r);
        assertThat(out).contains("[output truncated: " + (60000 - BashTool.MAX_OUTPUT_BYTES) + " more bytes]");
    }
}
