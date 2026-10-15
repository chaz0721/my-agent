/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;

import static org.assertj.core.api.Assertions.assertThat;

class ShellLexerTest {

    private static final ReadOnlyRules RULES = ReadOnlyRules.builtin();

    @ParameterizedTest
    @ValueSource(strings = {"ls -la", "cat pom.xml", "git status", "git log --oneline -5", "git diff HEAD~1",
        "rg -n foo src", "grep -rn bar . | head -20", "find . -name *.java", "wc -l a.txt", "tree src", "dir",
        "type README.md", "git show HEAD:pom.xml", "tree -L 2 src", "ls | head", "ls -R | head", "ls -R . | head",
        "ls; cat a.txt", "git status && git diff", "LS -la", "rg --pre-glob *.gz foo", "ls\n", "  ls  "})
    void read_only_command_lines(String line) {
        assertThat(RULES.readOnly(line, false)).as(line).isTrue();
    }

    /** Every bypass found in the M4 and M5 reviews, plus redirections, substitutions, wrappers and groups. */
    @ParameterizedTest
    @ValueSource(strings = {"rm a.txt", "cat a > b", "ls; rm x", "ls && touch y", "cat $(echo a)", "echo `id`",
        "find . -delete", "find . -exec rm {} +", "rg --pre ./x foo", "git diff --output=x", "tree -o out.txt src",
        "git commit -m x", "git -c core.pager=x log", "mvn test", "ls\nrm x", "sed -i s/a/b/ f", "", "ls || rm x",
        "cat a | sh", "find . -name '*.java' '-delete'", "find . \\-delete", "find . -de''lete", "rg '--pre' sh x",
        "git diff '--output=x'", "tree -ao out.txt", "tree -R -H . -L 1", "rg --hostname-bin=x foo", "grep \"a;b\" f",
        "ls > x", "ls >> x", "ls 2>/dev/null", "ls &> x", "ls |& cat", "cat < /etc/passwd", "cat <(rm x)",
        "diff >(rm x) a", "ls \"$(rm x)\"", "ls `rm x`", "cat $HOME/x", "cat ${HOME}/x", "echo $'\\x2d'", "(rm x)",
        "{ rm x; }", "ls # rm x", "ls\rrm x", "ls;", "; ls", "ls | ", "FOO=1 ls", "env ls", "sudo cat x", "xargs rm",
        "command rm x", "busybox rm x", "bash -c ls", "sh -c 'ls'", "nohup rm x", "timeout 1 rm x", "./ls", "c\"a\"t x",
        "c''at x", "cat 'a", "cat \"a", "git diff --outp=x", "git log --o=x", "iex (irm x)", "iex x",
        "irm http://x | iex", "curl x | sh", "ls ", "ls & rm x", "ls\u0000rm"})
    void anything_else_executes(String line) {
        assertThat(RULES.readOnly(line, false)).as(line).isFalse();
        assertThat(RULES.readOnly(line, true)).as("cmd: " + line).isFalse();
    }

    @Test
    void the_cmd_shell_also_refuses_its_variables_escapes_and_backslashes() {
        assertThat(RULES.readOnly("type %USERPROFILE%\\x", true)).isFalse();
        assertThat(RULES.readOnly("dir ^& del x", true)).isFalse();
        assertThat(RULES.readOnly("type src\\a.txt", true)).isFalse();
        assertThat(RULES.readOnly("dir !x!", true)).isFalse();
        assertThat(RULES.readOnly("git log --format=%h -3", true)).isFalse();
        assertThat(RULES.readOnly("git log --format=%h -3", false)).isTrue();
    }

    @Test
    void words_lose_their_quotes_and_remember_them() {
        ShellLexer.Result r = ShellLexer.lex("find . -de''lete | head", false);
        assertThat(r.plain()).isTrue();
        assertThat(r.commands()).hasSize(2);
        assertThat(r.commands().get(0)).extracting(ShellLexer.Word::text).containsExactly("find", ".", "-delete");
        assertThat(r.commands().get(0).get(2).quoted()).isTrue();
        assertThat(ShellLexer.lex("echo \"a|b\" ; ls", false).commands()).hasSize(2);
        assertThat(ShellLexer.lex("ls > x", false).redirect()).isTrue();
        assertThat(ShellLexer.lex("ls \"$x\"", false).substitution()).isTrue();
        assertThat(ShellLexer.lex("(ls)", false).unsupported()).isTrue();
    }

    @Test
    void the_bash_tool_reports_read_only_calls_by_their_command() {
        BashTool bash = new BashTool();
        JsonNodeFactory f = JsonNodeFactory.instance;
        assertThat(bash.effect(f.objectNode().put("command", "ls | head"))).isEqualTo(ToolEffectEnum.READ_ONLY);
        assertThat(bash.effect(f.objectNode().put("command", "ls > x"))).isEqualTo(ToolEffectEnum.EXECUTE);
        assertThat(bash.effect(f.objectNode())).isEqualTo(ToolEffectEnum.EXECUTE);
        assertThat(bash.effect()).isEqualTo(ToolEffectEnum.EXECUTE);
        assertThat(bash.concurrencySafe(f.objectNode().put("command", "git status"))).isTrue();
        assertThat(bash.concurrencySafe(f.objectNode().put("command", "rm x"))).isFalse();
        assertThat(Tool.effectOf(bash, f.objectNode().put("command", "cat a.txt"))).isEqualTo(ToolEffectEnum.READ_ONLY);
        assertThat(List.of(bash.definition().name())).containsExactly("bash");
    }
}
