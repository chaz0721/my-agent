/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalInteractionImplTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private TerminalInteractionImpl answering(String input) {
        PlainRendererImpl r = new PlainRendererImpl(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8, new PrintStream(out, true, StandardCharsets.UTF_8));
        return new TerminalInteractionImpl(r);
    }

    @Test
    void returns_the_choice_as_given_ignoring_case() {
        assertThat(answering("A\n").ask("run it?", List.of("y", "n", "a"))).isEqualTo("a");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("run it?").contains("[y/n/a]");
    }

    @Test
    void invalid_answers_are_asked_again_then_give_up() {
        assertThat(answering("maybe\ny\n").confirm("ok?")).isTrue();
        assertThat(answering("1\n2\n3\ny\n").ask("ok?", List.of("y", "n"))).isEmpty();
    }

    @Test
    void end_of_input_is_no_answer() {
        assertThat(answering("").ask("ok?", List.of("y", "n"))).isEmpty();
        assertThat(answering("").confirm("ok?")).isFalse();
        assertThat(answering("n\n").confirm("ok?")).isFalse();
    }

    @Test
    void control_characters_in_the_prompt_are_made_visible() {
        answering("y\n").ask("line1\n\ttab\rover\u001b[2K\u001b]0;title\u0007\u009b", List.of("y"));
        String shown = out.toString(StandardCharsets.UTF_8);
        assertThat(shown).contains("line1\n\ttab\\x0dover\\x1b[2K\\x1b]0;title\\x07\\x9b");
        assertThat(shown.chars().filter(c -> c == 0x1b || c == 0x07 || c == 0x9b)).isEmpty();
        assertThat(TerminalInteractionImpl.visible("ok\u007f")).isEqualTo("ok\\x7f");
    }
}
