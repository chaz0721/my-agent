/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.tool.ToolEffectEnum;

import static org.assertj.core.api.Assertions.assertThat;

class PathGuardTest {

    @TempDir
    Path cwd;
    @TempDir
    Path outside;

    private static final FakeTool WRITE = new FakeTool("write", ToolEffectEnum.WRITE_FS);
    private static final FakeTool READ = new FakeTool("read", ToolEffectEnum.READ_ONLY);

    private static String json(Path p) {
        return "{\"path\":\"" + p.toString().replace("\\", "\\\\") + "\"}";
    }

    @Test
    void writes_inside_the_working_directory_pass() {
        PathGuard g = new PathGuard(cwd, List.of());
        assertThat(g.check(FakeTool.call("write", "{\"path\":\"src/a.txt\"}"), WRITE)).isEmpty();
        assertThat(g.check(FakeTool.call("write", json(cwd.resolve("b.txt"))), WRITE)).isEmpty();
    }

    @Test
    void writes_outside_are_refused_but_reads_are_not_guarded() {
        PathGuard g = new PathGuard(cwd, List.of());
        assertThat(g.check(FakeTool.call("write", json(outside.resolve("x.txt"))), WRITE))
            .hasValueSatisfying(r -> assertThat(r).startsWith("path outside the working directory"));
        assertThat(g.check(FakeTool.call("write", "{\"path\":\"../escape.txt\"}"), WRITE)).isPresent();
        assertThat(g.check(FakeTool.call("read", json(outside.resolve("x.txt"))), READ)).isEmpty();
        assertThat(g.check(FakeTool.call("write", "{\"content\":\"no path\"}"), WRITE)).isEmpty();
    }

    @Test
    void allowed_directories_extend_the_working_directory() {
        PathGuard g = new PathGuard(cwd, List.of(outside));
        assertThat(g.check(FakeTool.call("write", json(outside.resolve("deep/x.txt"))), WRITE)).isEmpty();
    }

    @Test
    void a_link_inside_that_points_outside_is_refused() throws IOException {
        Path link = cwd.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic links not available: " + e.getMessage());
        }
        PathGuard g = new PathGuard(cwd, List.of());
        assertThat(g.check(FakeTool.call("write", "{\"path\":\"link/new/file.txt\"}"), WRITE)).isPresent();
    }
}
