/* pai-next — Apache-2.0 */
package dev.pai.core.prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectContextTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    @Test
    void files_load_in_order_with_imports() throws IOException {
        Files.writeString(home.resolve("PAI.md"), "home rules");
        Files.writeString(cwd.resolve("PAI.md"), "project rules\n@docs/more.md\n@missing.md");
        Files.createDirectories(cwd.resolve("docs"));
        Files.writeString(cwd.resolve("docs/more.md"), "imported\n@../PAI.md");
        Files.createDirectories(cwd.resolve(".pai"));
        Files.writeString(cwd.resolve(".pai/PAI.md"), "dot pai");
        Files.writeString(cwd.resolve("PAI.local.md"), "local");
        String text = ProjectContext.load(home, cwd).orElseThrow();
        assertThat(text).startsWith("<project-context>").endsWith("</project-context>");
        assertThat(text.indexOf("home rules")).isLessThan(text.indexOf("project rules"));
        assertThat(text.indexOf("project rules")).isLessThan(text.indexOf("imported"));
        assertThat(text.indexOf("imported")).isLessThan(text.indexOf("dot pai"));
        assertThat(text.indexOf("dot pai")).isLessThan(text.indexOf("local"));
        assertThat(text).contains("@missing.md");
        assertThat(text.split("project rules", -1)).hasSize(2);
    }

    @Test
    void nothing_to_load_and_budget() throws IOException {
        assertThat(ProjectContext.load(home, cwd)).isEmpty();
        Files.writeString(cwd.resolve("PAI.md"), "a".repeat(ProjectContext.MAX_CHARS * 2));
        String text = ProjectContext.load(home, cwd).orElseThrow();
        assertThat(text).contains("[project context truncated at 8000 characters]");
        assertThat(text.length()).isLessThan(ProjectContext.MAX_CHARS + 200);
    }

    @Test
    void untrusted_projects_only_import_relative_paths_inside() throws IOException {
        Path outside = home.resolve("secret.md");
        Files.writeString(outside, "SECRET");
        Files.createDirectories(cwd.resolve("docs"));
        Files.writeString(cwd.resolve("docs/inside.md"), "INSIDE");
        Files.writeString(cwd.resolve("PAI.md"),
            "@docs/inside.md\n@" + outside.toAbsolutePath() + "\n@docs/../docs/inside.md\n@../secret.md");
        String untrusted = ProjectContext.load(home, cwd, false).orElseThrow();
        assertThat(untrusted).contains("INSIDE").doesNotContain("SECRET");
        assertThat(untrusted).contains("@" + outside.toAbsolutePath()).contains("@docs/../docs/inside.md");
        String trusted = ProjectContext.load(home, cwd, true).orElseThrow();
        assertThat(trusted).contains("SECRET");
    }

    @Test
    void the_user_file_may_import_anything_even_in_untrusted_projects() throws IOException {
        Path other = cwd.resolve("elsewhere.md");
        Files.writeString(other, "USER IMPORT");
        Files.writeString(home.resolve("PAI.md"), "@" + other.toAbsolutePath());
        assertThat(ProjectContext.load(home, cwd, false).orElseThrow()).contains("USER IMPORT");
    }

    private static boolean link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    @Test
    void untrusted_projects_do_not_follow_links_out_of_the_working_directory() throws IOException {
        Path secret = home.resolve("secret.md");
        Files.writeString(secret, "SECRET");
        Files.createDirectories(cwd.resolve("docs"));
        Files.writeString(cwd.resolve("docs/inside.md"), "INSIDE");
        Assumptions.assumeTrue(link(cwd.resolve("docs/leak.md"), secret), "symbolic links not available");
        Files.writeString(cwd.resolve("PAI.md"), "@docs/inside.md\n@docs/leak.md");
        String untrusted = ProjectContext.load(home, cwd, false).orElseThrow();
        assertThat(untrusted).contains("INSIDE", "@docs/leak.md").doesNotContain("SECRET");
        assertThat(ProjectContext.load(home, cwd, true).orElseThrow()).contains("SECRET");
    }

    @Test
    void an_untrusted_candidate_file_that_links_outside_is_skipped() throws IOException {
        Path secret = home.resolve("secret.md");
        Files.writeString(secret, "SECRET");
        Assumptions.assumeTrue(link(cwd.resolve("PAI.local.md"), secret), "symbolic links not available");
        Files.writeString(cwd.resolve("PAI.md"), "rules");
        String untrusted = ProjectContext.load(home, cwd, false).orElseThrow();
        assertThat(untrusted).contains("rules").doesNotContain("SECRET");
    }
}
