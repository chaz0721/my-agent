/* pai-next — Apache-2.0 */
package dev.pai.ext.verify;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.model.CancelToken;
import dev.pai.tools.BuiltinToolsExtension;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs through the real built-in bash tool. */
@Tag("slow")
class VerifyRunnerTest {

    @TempDir
    Path cwd;

    private VerifyRunner runner() {
        ExtensionHost h =
            new ExtensionLoader().install(List.of(new BuiltinToolsExtension()), ExtensionHost.Settings.of(cwd, cwd));
        return new VerifyRunner(h.tools()::get, cwd);
    }

    private static VerifyRule rule(String run, int timeout) {
        return new VerifyRule(Pattern.compile("write"), "**", run, timeout, false);
    }

    @Test
    void exit_codes_decide_and_the_output_is_reported() {
        VerifyRunner r = runner();
        assertThat(r.run(rule("echo fine", 30), CancelToken.none()))
            .isEqualTo(new VerifyRunner.Outcome(true, "[verify: echo fine] passed"));
        VerifyRunner.Outcome failed = r.run(rule("echo broken; exit 3", 30), CancelToken.none());
        assertThat(failed.passed()).isFalse();
        assertThat(failed.report()).startsWith("[verify: echo broken; exit 3] failed (exit 3)").contains("broken");
    }

    @Test
    void a_command_that_runs_too_long_is_killed() {
        VerifyRunner.Outcome o = runner().run(rule("sleep 20", 1), CancelToken.none());
        assertThat(o.passed()).isFalse();
        assertThat(o.report()).contains("failed (exit -1)", "timed out after 1s");
    }

    @Test
    void without_the_bash_tool_nothing_runs() {
        VerifyRunner.Outcome o =
            new VerifyRunner(n -> java.util.Optional.empty(), cwd).run(rule("echo x", 5), CancelToken.none());
        assertThat(o.report()).isEqualTo("[verify: echo x] skipped: the bash tool is not available");
    }

    @Test
    void the_python_rule_does_not_run_modules_from_the_working_directory() throws java.io.IOException {
        java.nio.file.Files.writeString(cwd.resolve("pyproject.toml"), "");
        java.nio.file.Files.createDirectories(cwd.resolve("pkg"));
        java.nio.file.Files.writeString(cwd.resolve("pkg/a.py"), "def f():\n    return 1\n");
        String payload = "open('PWNED', 'w').write('x')\n";
        java.nio.file.Files.writeString(cwd.resolve("compileall.py"), payload);
        java.nio.file.Files.writeString(cwd.resolve("py_compile.py"), payload);
        VerifyRule rule = VerifyExtension.builtin(cwd).get(0);
        java.util.Optional<VerifyRunner.Outcome> o = runner().run(rule, List.of("pkg/a.py"), CancelToken.none());
        org.junit.jupiter.api.Assumptions.assumeTrue(o.isPresent(), "no python here");
        assertThat(o.get().passed()).as(o.get().report()).isTrue();
        assertThat(cwd.resolve("PWNED")).doesNotExist();
        assertThat(cwd.resolve("pkg/__pycache__")).doesNotExist();
    }
}
