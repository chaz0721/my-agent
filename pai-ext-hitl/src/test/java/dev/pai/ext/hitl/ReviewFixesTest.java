/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.pai.core.loop.ToolDecision;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The M7 review findings P1-1, P1-2, P1-3, P2-4 and P2-5 of the approvals extension. */
class ReviewFixesTest {

    @TempDir
    Path cwd;
    @TempDir
    Path elsewhere;
    @TempDir
    Path home;

    private final List<String> asked = new ArrayList<>();
    private final Tool bash = new FakeBashTool();

    private ApprovalHooks hooks(ApprovalModeEnum mode, Permissions permissions, boolean trusted, Approver approver) {
        return new ApprovalHooks(new ApprovalHooks.Parts(mode, new PathGuard(cwd, List.of()), CommandGuard.builtin(),
            approver, new CallPreview(cwd)), permissions, n -> {
            }, trusted);
    }

    private ApprovalHooks hooks(ApprovalModeEnum mode, Permissions permissions, boolean trusted) {
        return hooks(mode, permissions, trusted, (c, t, r) -> {
            asked.add(r);
            return ApprovalDecisionEnum.ALLOW_ALWAYS;
        });
    }

    private static ContentBlock.ToolCall bash(String command) {
        return new ContentBlock.ToolCall("c1", "bash", Json.mapper().createObjectNode().put("command", command));
    }

    private Permissions denyGitPush() throws IOException {
        Path file = home.resolve(Permissions.FILE_NAME);
        Files.writeString(file, "{\"deny\":[{\"tool\":\"bash\",\"pattern\":\"git push*\"}]}");
        return new Permissions(Optional.of(file), Optional.empty(), false, cwd);
    }

    @Test
    void a_read_command_whose_word_links_outside_is_asked_about() throws IOException {
        Path secret = elsewhere.resolve("secret.txt");
        Files.writeString(secret, "token");
        try {
            Files.createSymbolicLink(cwd.resolve("notes"), secret);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links not available");
        }
        ApprovalHooks h = hooks(ApprovalModeEnum.AUTO_READ, Permissions.none(), true);
        assertThat(h.beforeToolCall(bash("cat notes"), bash, null).allowed()).isTrue();
        assertThat(asked).singleElement().asString().startsWith(ApprovalHooks.READ_OUTSIDE_NOTE)
            .contains("outside the working directory");
    }

    @Test
    void a_link_glued_to_a_short_option_as_letters_only_is_asked_about() throws IOException {
        Path secret = elsewhere.resolve("patterns.txt");
        Files.writeString(secret, "token");
        try {
            Files.createSymbolicLink(cwd.resolve("lnk"), secret);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links not available");
        }
        ApprovalHooks h = hooks(ApprovalModeEnum.AUTO_READ, Permissions.none(), true);
        assertThat(h.beforeToolCall(bash("grep -flnk ."), bash, null).allowed()).isTrue();
        assertThat(asked).singleElement().asString().startsWith(ApprovalHooks.READ_OUTSIDE_NOTE);
        asked.clear();
        assertThat(h.beforeToolCall(bash("grep -flocal ."), bash, null).allowed()).isTrue();
        assertThat(asked).as("letters naming nothing outside").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"git pu^sh", "git p%x%ush", "git push!x!", "timeout 5s git push"})
    void on_windows_cmd_escapes_and_variables_hide_the_command(String command) throws IOException {
        Path file = home.resolve(Permissions.FILE_NAME);
        Files.writeString(file, "{\"deny\":[{\"tool\":\"bash\",\"pattern\":\"git push*\"}]}");
        Permissions windows = new Permissions(Optional.of(file), Optional.empty(), true, cwd);
        assertThat(windows.denying(bash(command)).isPresent() || windows.uncertain(bash(command))).as(command).isTrue();
        ApprovalHooks h = hooks(ApprovalModeEnum.YOLO, windows, true, (c, t, r) -> ApprovalDecisionEnum.NO_ANSWER);
        assertThat(h.beforeToolCall(bash(command), new FakeTool("bash", ToolEffectEnum.EXECUTE), null).allowed())
            .as(command).isFalse();
        assertThat(new Permissions(Optional.of(file), Optional.empty(), false, cwd).uncertain(bash("git pu^sh")))
            .as("bash keeps ^ as a plain character").isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"grep -rf/etc/passwd .", "cat -", "cat ../x", "grep --file=/etc/x a", "cat a:/etc/x"})
    void read_commands_that_may_read_elsewhere_are_asked_about(String command) {
        ApprovalHooks h = hooks(ApprovalModeEnum.PLAN, Permissions.none(), true);
        assertThat(h.beforeToolCall(bash(command), bash, null).allowed()).as(command).isTrue();
        assertThat(asked).as(command).singleElement().asString().startsWith(ApprovalHooks.READ_OUTSIDE_NOTE);
        assertThat(PathGuard.commandMayLeaveCwd("grep -rf/etc/passwd .")).isTrue();
    }

    @Test
    void read_commands_inside_run_without_asking() {
        ApprovalHooks h = hooks(ApprovalModeEnum.AUTO_READ, Permissions.none(), true);
        for (String c : List.of("cat src/a.txt", "ls -la", "grep -n5 x a.txt", "git log --oneline -5")) {
            assertThat(h.beforeToolCall(bash(c), bash, null).allowed()).as(c).isTrue();
        }
        assertThat(asked).isEmpty();
    }

    @Test
    void git_reads_are_free_only_in_a_trusted_project() {
        ApprovalHooks untrusted = hooks(ApprovalModeEnum.AUTO_READ, Permissions.none(), false);
        assertThat(untrusted.beforeToolCall(bash("git status"), bash, null).allowed()).isTrue();
        assertThat(asked).singleElement().asString().startsWith(ApprovalHooks.GIT_UNTRUSTED_NOTE);
        assertThat(untrusted.beforeToolCall(bash("ls"), bash, null).allowed()).isTrue();
        assertThat(asked).hasSize(1);
        ApprovalHooks plan = hooks(ApprovalModeEnum.PLAN, Permissions.none(), false, (c, t, r) -> {
            asked.add(r);
            return ApprovalDecisionEnum.NO_ANSWER;
        });
        assertThat(plan.beforeToolCall(bash("git log"), bash, null).allowed()).isFalse();
        asked.clear();
        assertThat(hooks(ApprovalModeEnum.AUTO_READ, Permissions.none(), true)
            .beforeToolCall(bash("git status"), bash, null).allowed()).isTrue();
        assertThat(asked).isEmpty();
    }

    /** The review's deny bypasses: each is denied, or at least asked about, also in yolo mode. */
    @ParameterizedTest
    @ValueSource(strings = {"git  push", "git\tpush", "git \"push\"", "git -C . push", "command git push",
        "env git push", "X=1 git push", "/usr/bin/git push", "{ git push; }", "if true; then git push; fi",
        "sudo -u x git push origin", "nice -n 5 git push", "sh -c 'git push'", "echo x | xargs git push",
        "timeout 5s git push"})
    void deny_rules_cannot_be_bypassed(String command) throws IOException {
        for (ApprovalModeEnum mode : List.of(ApprovalModeEnum.YOLO, ApprovalModeEnum.AUTO_READ)) {
            asked.clear();
            ApprovalHooks h = hooks(mode, denyGitPush(), true, (c, t, r) -> {
                asked.add(r);
                return ApprovalDecisionEnum.NO_ANSWER;
            });
            ToolDecision d = h.beforeToolCall(bash(command), new FakeTool("bash", ToolEffectEnum.EXECUTE), null);
            assertThat(d.allowed()).as(mode + ": " + command).isFalse();
            boolean denied = d.reason().orElse("").contains("permission rule");
            assertThat(denied || asked.size() == 1).as(mode + ": " + command).isTrue();
        }
    }

    @Test
    void an_unclear_command_is_asked_once_even_with_always_and_allow_rules() throws IOException {
        Path file = home.resolve(Permissions.FILE_NAME);
        Files.writeString(file,
            "{\"allow\":[{\"tool\":\"bash\"}],\"deny\":[{\"tool\":\"bash\",\"pattern\":\"git push*\"}]}");
        ApprovalHooks h =
            hooks(ApprovalModeEnum.ASK, new Permissions(Optional.of(file), Optional.empty(), false, cwd), true);
        Tool exec = new FakeTool("bash", ToolEffectEnum.EXECUTE);
        assertThat(h.beforeToolCall(bash("echo \"$(date)\""), exec, null).allowed()).isTrue();
        assertThat(h.beforeToolCall(bash("echo \"$(date)\""), exec, null).allowed()).isTrue();
        assertThat(asked).hasSize(2).allSatisfy(r -> assertThat(r).startsWith(ApprovalHooks.UNCERTAIN_NOTE));
        assertThat(h.beforeToolCall(bash("mvn test"), exec, null).allowed()).as("plain and allowed").isTrue();
        assertThat(asked).hasSize(2);
        assertThat(h.beforeToolCall(bash("git -C x push"), exec, null).reason().orElseThrow())
            .contains("permission rule");
    }

    @Test
    void without_bash_deny_rules_unclear_commands_follow_the_mode() {
        ApprovalHooks h = hooks(ApprovalModeEnum.YOLO, Permissions.none(), true);
        assertThat(
            h.beforeToolCall(bash("echo \"$(date)\""), new FakeTool("bash", ToolEffectEnum.EXECUTE), null).allowed())
            .isTrue();
        assertThat(asked).isEmpty();
    }

    @Test
    void path_allow_rules_match_the_normalized_path_inside_the_working_directory() throws IOException {
        Path file = home.resolve(Permissions.FILE_NAME);
        Files.writeString(file, "{\"allow\":[{\"tool\":\"edit\",\"pattern\":\"src/*\"}]}");
        Permissions p = new Permissions(Optional.of(file), Optional.empty(), false, cwd);
        assertThat(p.allows(FakeTool.call("edit", "{\"path\":\"src/A.java\"}"))).isTrue();
        assertThat(p.allows(FakeTool.call("edit", "{\"path\":\"./src/A.java\"}"))).isTrue();
        assertThat(p.allows(FakeTool.call("edit", "{\"path\":\"src/../.git/hooks/pre-commit\"}"))).isFalse();
        assertThat(p.allows(FakeTool.call("edit", "{\"path\":\"src/../../outside/x\"}"))).isFalse();
        assertThatThrownBy(() -> p.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X=1 mvn test", "env mvn test", "sudo mvn test", "sh x.sh", "bash x.sh", "pwsh x",
        "powershell x", "cmd /c x", "python3 x.py", "python x.py", "node x.js", "npx x", "xargs rm", "eval x", "exec x",
        "command x", "timeout 5 mvn test", "stdbuf -oL mvn test", "watch ls", "ionice -c3 mvn test",
        "chrt -f 99 mvn test"})
    void no_rule_is_proposed_for_wrappers_and_interpreters(String command) {
        assertThat(ApprovalHooks.proposedPattern(bash(command))).as(command).isEmpty();
        assertThat(ApprovalHooks.proposedPattern(bash("mvn -q test"))).contains("mvn *");
    }
}
