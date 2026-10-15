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
    void an_exact_choice_wins_so_that_a_and_capital_a_differ() {
        assertThat(answering("A\n").ask("run it?", List.of("y", "n", "a", "A"))).isEqualTo("A");
        assertThat(answering("a\n").ask("run it?", List.of("y", "n", "a", "A"))).isEqualTo("a");
        assertThat(answering("Y\n").ask("run it?", List.of("y", "n", "a", "A"))).isEqualTo("y");
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

    @Test
    void direction_controls_and_zero_width_characters_are_made_visible() {
        String tricky = "a\u202eb\u2066c\u2069d\u200be\u200df\u2060g\ufeffh";
        assertThat(TerminalInteractionImpl.visible(tricky))
            .isEqualTo("a\\u202eb\\u2066c\\u2069d\\u200be\\u200df\\u2060g\\ufeffh");
        assertThat(TerminalInteractionImpl.visible("\u4e2d\u00e9")).isEqualTo("\u4e2d\u00e9");
    }

    @Test
    void a_terminal_already_reading_on_another_thread_is_no_answer() {
        Renderer busy = (Renderer)java.lang.reflect.Proxy.newProxyInstance(Renderer.class.getClassLoader(),
            new Class<?>[] {Renderer.class}, (proxy, method, args) -> {
                if ("readLine".equals(method.getName())) {
                    throw new IllegalStateException("already reading");
                }
                return null;
            });
        TerminalInteractionImpl ui = new TerminalInteractionImpl(busy);
        assertThat(ui.ask("ok?", List.of("y", "n"))).isEmpty();
        assertThat(ui.confirm("ok?")).isFalse();
    }
}
