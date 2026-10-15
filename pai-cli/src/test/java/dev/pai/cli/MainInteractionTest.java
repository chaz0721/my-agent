/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Piped input never answers questions: no trust question, nothing recorded, approvals denied. {@code PAI_INTERACTIVE=1}
 * declares piped input able to answer.
 */
class MainInteractionTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private String run(String input, Map<String, String> extraEnv, String... extraArgs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, String> env = new HashMap<>(extraEnv);
        env.put("PAI_HOME", home.toString());
        String[] args = new String[extraArgs.length + 4];
        args[0] = "--provider";
        args[1] = "fake";
        args[2] = "--cwd";
        args[3] = cwd.toString();
        System.arraycopy(extraArgs, 0, args, 4, extraArgs.length);
        int code = Main.run(args, new Main.Io(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()), false),
            env);
        assertThat(code).isZero();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void piped_input_is_never_asked_and_nothing_is_recorded() throws IOException {
        Files.writeString(cwd.resolve(".env"), "A=1\n");
        String out = run("y\n/quit\n", Map.of());
        assertThat(out).doesNotContain(ProjectTrust.QUESTION).contains(ProjectTrust.UNTRUSTED, "echo: y");
        assertThat(out)
            .contains("approvals: mode auto-read but no terminal to ask; calls that need approval are denied");
        assertThat(home.resolve(ProjectTrust.FILE_NAME)).doesNotExist();
    }

    @Test
    void declared_interactive_input_answers_the_trust_question() throws IOException {
        Files.writeString(cwd.resolve(".env"), "A=1\n");
        String out = run("y\n/quit\n", Map.of(Main.INTERACTIVE_ENV, "1"));
        assertThat(out).contains(ProjectTrust.QUESTION, "! trusted project: loaded ./.env").doesNotContain("echo: y");
        assertThat(Files.readString(home.resolve(ProjectTrust.FILE_NAME))).contains("true");
        assertThat(out).doesNotContain("no terminal to ask");
    }

    @Test
    void end_of_input_at_the_question_records_nothing() throws IOException {
        Files.writeString(cwd.resolve(".env"), "A=1\n");
        run("", Map.of(Main.INTERACTIVE_ENV, "1"));
        assertThat(home.resolve(ProjectTrust.FILE_NAME)).doesNotExist();
    }

    @Test
    void approval_option_sets_the_mode() {
        assertThat(run("/approvals\n/quit\n", Map.of(), "--approval", "ask")).contains("approvals: mode ask");
        assertThat(run("/approvals\n/quit\n", Map.of())).contains("approvals: mode auto-read");
        assertThat(run("/approvals\n/quit\n", Map.of(Main.APPROVAL_ENV, "plan"))).contains("approvals: mode plan");
        assertThat(run("/approvals\n/quit\n", Map.of(Main.APPROVAL_ENV, "plan"), "--approval", "ask"))
            .contains("approvals: mode ask");
        assertThat(System.getProperty("pai.approval")).isNull();
    }
}
