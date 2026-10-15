/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.ext.UserInteraction;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectTrustTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private final List<String> asked = new ArrayList<>();

    private Optional<UserInteraction> answering(boolean yes) {
        return Optional.of(new UserInteraction() {
            @Override
            public String ask(String prompt, List<String> choices) {
                asked.add(prompt);
                return yes ? "y" : "n";
            }

            @Override
            public boolean confirm(String prompt) {
                return "y".equals(ask(prompt, List.of("y", "n")));
            }
        });
    }

    @Test
    void asks_once_per_directory_with_project_config_and_remembers_the_answer() throws IOException {
        Files.writeString(cwd.resolve(".env"), "A=1\n");
        ProjectTrust trust = new ProjectTrust(home);
        assertThat(trust.decide(cwd, answering(false))).isFalse();
        assertThat(trust.decide(cwd, answering(true))).isFalse();
        assertThat(asked).containsExactly(ProjectTrust.QUESTION);
        trust.record(cwd, true);
        assertThat(new ProjectTrust(home).decide(cwd, Optional.empty())).isTrue();
        assertThat(Files.readString(home.resolve(ProjectTrust.FILE_NAME))).contains("\"projects\"");
    }

    @Test
    void plain_directories_and_non_interactive_runs_are_not_asked_and_not_trusted() throws IOException {
        ProjectTrust trust = new ProjectTrust(home);
        assertThat(trust.decide(cwd, answering(true))).isFalse();
        Files.createDirectories(cwd.resolve(".pai"));
        assertThat(trust.decide(cwd, Optional.empty())).isFalse();
        assertThat(asked).isEmpty();
        assertThat(trust.decide(cwd, answering(true))).isTrue();
    }

    @Test
    void the_key_is_the_normalized_real_path() throws IOException {
        Path sub = Files.createDirectories(cwd.resolve("a"));
        assertThat(ProjectTrust.key(sub.resolve("..").resolve("a"))).isEqualTo(sub.toRealPath().toString());
        assertThat(ProjectTrust.key(cwd.resolve("missing/../gone"))).isEqualTo(cwd.resolve("gone").toString());
    }

    @Test
    void a_corrupt_file_holds_no_answers() throws IOException {
        Files.writeString(home.resolve(ProjectTrust.FILE_NAME), "[not json");
        Files.writeString(cwd.resolve(".env"), "A=1\n");
        assertThat(new ProjectTrust(home).decide(cwd, Optional.empty())).isFalse();
        new ProjectTrust(home).record(cwd, true);
        assertThat(new ProjectTrust(home).decide(cwd, Optional.empty())).isTrue();
    }
}
