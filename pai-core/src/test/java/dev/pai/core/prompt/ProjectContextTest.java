/* pai-next — Apache-2.0 */
package dev.pai.core.prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
