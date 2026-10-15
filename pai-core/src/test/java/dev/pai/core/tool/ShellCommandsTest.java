/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ShellCommandsTest {

    @Test
    void plain_lines_split_into_simple_commands_and_words() {
        assertThat(ShellCommands.simpleCommands("ls -la | head  -5; git\tstatus && cat a || x & y"))
            .contains(List.of(List.of("ls", "-la"), List.of("head", "-5"), List.of("git", "status"),
                List.of("cat", "a"), List.of("x"), List.of("y")));
        assertThat(ShellCommands.simpleCommands("  ls\n")).contains(List.of(List.of("ls")));
        assertThat(ShellCommands.simpleCommands("git show HEAD:pom.xml ~/x")).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ls;", "; ls", "ls | ", "ls &", "a ;; b", "cat 'a'", "cat \"a\"", "cat a\\ b", "cat $x",
        "echo `id`", "ls > x", "cat < x", "ls |& cat", "cat *", "ls ?.txt", "ls [a-z]*", "(ls)", "{ ls; }", "ls # x",
        "ls\rrm", "ls\u0000", "ls\u007f"})
    void anything_else_is_unclean(String line) {
        assertThat(ShellCommands.simpleCommands(line)).as(line).isEmpty();
    }

    @Test
    void the_cmd_shell_also_refuses_its_variables_and_escapes() {
        assertThat(ShellCommands.simpleCommands("type %USERPROFILE%", true)).isEmpty();
        assertThat(ShellCommands.simpleCommands("dir ^& del x", true)).isEmpty();
        assertThat(ShellCommands.simpleCommands("dir !x!", true)).isEmpty();
        assertThat(ShellCommands.simpleCommands("git log --format=%h", false)).isPresent();
    }
}
