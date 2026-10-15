/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealPathsTest {

    @TempDir
    Path tmp;

    @Test
    void paths_inside_resolve_to_their_real_file_even_before_they_exist() throws IOException {
        Path cwd = Files.createDirectories(tmp.resolve("p"));
        Files.writeString(cwd.resolve("a.txt"), "x");
        Path real = cwd.toRealPath();
        assertThat(RealPaths.inside(cwd, "a.txt")).contains(real.resolve("a.txt"));
        assertThat(RealPaths.inside(cwd, "new/b.txt")).contains(real.resolve("new/b.txt"));
        assertThat(RealPaths.inside(cwd, real.resolve("a.txt").toString())).contains(real.resolve("a.txt"));
    }

    @Test
    void paths_outside_or_invalid_are_empty() throws IOException {
        Path cwd = Files.createDirectories(tmp.resolve("p"));
        Files.writeString(tmp.resolve("secret"), "x");
        assertThat(RealPaths.inside(cwd, "../secret")).isEmpty();
        assertThat(RealPaths.inside(cwd, tmp.resolve("secret").toString())).isEmpty();
        assertThat(RealPaths.inside(cwd, "bad\0name")).isEmpty();
    }

    @Test
    void links_are_followed_and_dangling_ones_fail_closed() throws IOException {
        Path cwd = Files.createDirectories(tmp.resolve("p"));
        Files.writeString(tmp.resolve("outside.txt"), "x");
        Files.writeString(cwd.resolve("a.txt"), "x");
        try {
            Files.createSymbolicLink(cwd.resolve("out"), tmp.resolve("outside.txt"));
            Files.createSymbolicLink(cwd.resolve("same"), cwd.resolve("a.txt"));
            Files.createSymbolicLink(cwd.resolve("dangling"), tmp.resolve("missing"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links not available");
        }
        assertThat(RealPaths.inside(cwd, "out")).isEmpty();
        assertThat(RealPaths.inside(cwd, "same")).contains(cwd.toRealPath().resolve("a.txt"));
        assertThat(RealPaths.inside(cwd, "dangling")).isEmpty();
    }
}
