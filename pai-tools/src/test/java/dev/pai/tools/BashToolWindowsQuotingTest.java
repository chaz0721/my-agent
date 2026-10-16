/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.tool.ToolResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Git Bash on Windows gets the command as a script file: quotes, globs, {@code $} and newlines reach bash unchanged
 * (passed as {@code -lc <command>} they were re-parsed by the MSYS runtime), and the script is gone afterwards.
 */
@Tag("slow")
@EnabledOnOs(OS.WINDOWS)
@EnabledIf("gitBash")
class BashToolWindowsQuotingTest {

    @TempDir
    Path dir;

    static boolean gitBash() {
        return BashTool.shell("true").viaScript();
    }

    private static List<Path> scripts() throws IOException {
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(p -> p.getFileName().toString().startsWith("pai-bash-")).toList();
        }
    }

    @Test
    void quotes_globs_dollars_and_newlines_survive_and_the_script_is_deleted() throws Exception {
        List<Path> before = scripts();
        String command = "printf '[%s]' \"a  b\" 'c d' x*y; echo\r\nv=single; echo \"dollar=$v\" 'lit=$v'\n"
            + "python_like='print(\"hi\")'; echo \"$python_like\"";
        ToolResult r = new BashTool().execute(TestContext.args().put("command", command), new TestContext(dir));
        String out = TestContext.text(r).replace("\r\n", "\n");
        assertThat(out).contains("[a  b][c d][x*y]\n", "dollar=single lit=$v\n", "print(\"hi\")")
            .endsWith("[exit code: 0]");
        assertThat(scripts()).containsExactlyInAnyOrderElementsOf(before);
    }
}
