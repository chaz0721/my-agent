/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class PathGuardTest {

    @TempDir
    Path cwd;
    @TempDir
    Path outside;

    private static String json(Path p) {
        return "{\"path\":\"" + p.toString().replace("\\", "\\\\") + "\"}";
    }

    private static void link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic links not available: " + e.getMessage());
        }
    }

    @Test
    void paths_inside_the_working_directory_pass() {
        PathGuard g = new PathGuard(cwd, List.of());
        assertThat(g.check(FakeTool.call("write", "{\"path\":\"src/a.txt\"}"))).isEmpty();
        assertThat(g.check(FakeTool.call("write", json(cwd.resolve("b.txt"))))).isEmpty();
        assertThat(g.check(FakeTool.call("write", "{\"content\":\"no path\"}"))).isEmpty();
    }

    @Test
    void paths_outside_are_reported_for_every_tool() {
        PathGuard g = new PathGuard(cwd, List.of());
        assertThat(g.check(FakeTool.call("write", json(outside.resolve("x.txt")))))
            .hasValueSatisfying(r -> assertThat(r).startsWith("path outside the working directory"));
        assertThat(g.check(FakeTool.call("write", "{\"path\":\"../escape.txt\"}"))).isPresent();
        assertThat(g.check(FakeTool.call("read", json(outside.resolve("x.txt"))))).isPresent();
    }

    @Test
    void allowed_directories_extend_the_working_directory() {
        PathGuard g = new PathGuard(cwd, List.of(outside));
        assertThat(g.check(FakeTool.call("write", json(outside.resolve("deep/x.txt"))))).isEmpty();
    }

    @Test
    void a_link_inside_that_points_outside_is_outside() {
        link(cwd.resolve("link"), outside);
        PathGuard g = new PathGuard(cwd, List.of());
        assertThat(g.check(FakeTool.call("write", "{\"path\":\"link/new/file.txt\"}"))).isPresent();
    }

    @Test
    void a_dangling_link_fails_closed() {
        link(cwd.resolve("dangling"), outside.resolve("not-yet-there.txt"));
        link(cwd.resolve("dangling-dir"), outside.resolve("missing-dir"));
        PathGuard g = new PathGuard(cwd, List.of());
        assertThat(g.check(FakeTool.call("write", "{\"path\":\"dangling\"}")))
            .hasValueSatisfying(r -> assertThat(r).contains("cannot be resolved"));
        assertThat(g.check(FakeTool.call("write", "{\"path\":\"dangling-dir/a/b.txt\"}"))).isPresent();
        assertThat(PathGuard.real(cwd.resolve("dangling"))).isEmpty();
    }
}
