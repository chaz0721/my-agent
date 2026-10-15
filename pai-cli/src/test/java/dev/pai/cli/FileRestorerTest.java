/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileRestorerTest {

    @TempDir
    Path cwd;
    @TempDir
    Path elsewhere;

    private Path file(String name, String text) throws IOException {
        Path p = cwd.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
        return p;
    }

    @Test
    void restores_the_latest_listed_files_with_their_current_content() throws IOException {
        file("src/A.java", "class A {}\n");
        file("b.txt", "bee");
        String text = new FileRestorer(cwd).restore(List.of("src/A.java", "b.txt")).orElseThrow();
        assertThat(text).startsWith(FileRestorer.OPEN).endsWith(FileRestorer.CLOSE)
            .contains("<file path=\"src/A.java\">\nclass A {}\n</file>\n", "<file path=\"b.txt\">\nbee\n</file>\n");
        assertThat(text.indexOf("b.txt")).as("latest first").isLessThan(text.indexOf("src/A.java"));
    }

    @Test
    void at_most_five_files_and_missing_ones_are_skipped() throws IOException {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            file("f" + i + ".txt", "file " + i);
            paths.add("f" + i + ".txt");
        }
        paths.add("missing.txt");
        paths.add("./f7.txt");
        String text = new FileRestorer(cwd).restore(paths).orElseThrow();
        assertThat(text.split("<file path=", -1)).hasSize(FileRestorer.MAX_FILES + 1);
        assertThat(text).contains("f7.txt", "f6.txt", "f5.txt", "f4.txt", "f3.txt").doesNotContain("f2.txt", "missing");
        assertThat(new FileRestorer(cwd).restore(List.of("missing.txt"))).isEmpty();
    }

    @Test
    void files_outside_the_working_directory_are_never_restored() throws IOException {
        Path secret = elsewhere.resolve("secret.txt");
        Files.writeString(secret, "token");
        file("in.txt", "inside");
        List<String> paths = List.of(secret.toString(), "../" + elsewhere.getFileName() + "/secret.txt", "in.txt");
        String text = new FileRestorer(cwd).restore(paths).orElseThrow();
        assertThat(text).contains("inside").doesNotContain("token");
    }

    @Test
    void a_link_that_leads_outside_is_skipped() throws IOException {
        Path secret = elsewhere.resolve("secret.txt");
        Files.writeString(secret, "token");
        try {
            Files.createSymbolicLink(cwd.resolve("link.txt"), secret);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links not available");
        }
        assertThat(new FileRestorer(cwd).restore(List.of("link.txt"))).isEmpty();
    }

    @Test
    void too_large_and_binary_files_are_skipped_and_the_total_is_bounded() throws IOException {
        file("big.txt", "x".repeat(FileRestorer.MAX_FILE_TOKENS * 4 + 100));
        Files.write(cwd.resolve("bin.dat"), new byte[] {(byte)0xff, (byte)0xfe, 0, 1});
        assertThat(new FileRestorer(cwd).restore(List.of("big.txt", "bin.dat"))).isEmpty();
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            file("m" + i + ".txt", "y".repeat(FileRestorer.MAX_FILE_TOKENS * 4 - 8));
            paths.add("m" + i + ".txt");
        }
        String text = new FileRestorer(cwd).restore(paths).orElseThrow();
        long tokens = (text.length() - FileRestorer.OPEN.length()) / 4L;
        assertThat(tokens).isLessThanOrEqualTo(FileRestorer.MAX_TOTAL_TOKENS + 100L);
        assertThat(text.split("<file path=", -1).length - 1).isBetween(1, FileRestorer.MAX_FILES);
    }
}
