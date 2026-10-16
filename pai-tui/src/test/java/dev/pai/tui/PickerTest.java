/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review P0: a choice box answers only to keys typed after it appeared, confirmed with Enter; type-ahead, pasted text,
 * modified arrows, function keys and split escape sequences never answer it.
 */
class PickerTest {

    private static final String QUESTION =
        "tool bash (execute)\n$ echo hi\nallow? y = yes, n = no, a = always for bash in "
            + "this session, A = always, saved as a rule (shown before saving)";
    private static final List<String> CHOICES = List.of("y", "n", "a", "A");
    /** Well after the guard time: what a person types once the box is on screen. */
    private static final long LATER = 1000L;

    /** Keys arriving at given times, read against a fake clock; after the last key, input ends. */
    private static final class Script implements Picker.Keys {
        private final Deque<long[]> keys = new ArrayDeque<>();
        private long now;
        int redraws;

        Script at(long millis, String text) {
            text.chars().forEach(c -> keys.add(new long[] {millis, c}));
            return this;
        }

        @Override
        public int read(long timeoutMillis) {
            long[] next = keys.peek();
            if (next != null && (timeoutMillis == 0 || next[0] <= now + timeoutMillis)) {
                keys.poll();
                now = Math.max(now, next[0]);
                return (int)next[1];
            }
            if (next == null && timeoutMillis == 0) {
                return -1;
            }
            now += timeoutMillis;
            return Picker.TIMEOUT;
        }

        @Override
        public long millis() {
            return now;
        }

        int left() {
            return keys.size();
        }
    }

    private static Optional<String> run(Script s) throws Exception {
        return Picker.of(QUESTION, CHOICES).run(s, () -> s.redraws++);
    }

    @Test
    void type_ahead_does_not_answer_and_a_later_choice_does() throws Exception {
        Script s = new Script().at(0, "Add tests\r").at(LATER, "n\r");
        assertThat(run(s)).contains("n");
        assertThat(run(new Script().at(0, "fix the bug\r").at(LATER, "\u001b"))).isEmpty();
        assertThat(run(new Script().at(0, "\r").at(LATER, "n\r"))).contains("n");
        assertThat(run(new Script().at(0, "A"))).isEmpty();
    }

    @Test
    void keys_right_after_the_box_appeared_are_ignored() throws Exception {
        assertThat(run(new Script().at(100, "y\r").at(LATER, "n\r"))).contains("n");
        assertThat(run(new Script().at(Picker.GUARD_MILLIS - 1, "A\r").at(LATER, "\u001b"))).isEmpty();
    }

    @Test
    void enter_answers_only_a_highlighted_choice_and_keys_only_move_the_highlight() throws Exception {
        assertThat(run(new Script().at(LATER, "\r").at(LATER + 500, "\u001b"))).isEmpty();
        assertThat(run(new Script().at(LATER, "y").at(LATER + 500, "\u001b"))).isEmpty();
        assertThat(run(new Script().at(LATER, "3").at(LATER + 500, "\u001b"))).isEmpty();
        assertThat(run(new Script().at(LATER, "3\r"))).contains("a");
        assertThat(run(new Script().at(LATER, "add tests\r").at(LATER + 500, "\u001b"))).isEmpty();
        assertThat(run(new Script().at(LATER, "yes\r").at(LATER + 500, "\u001b"))).isEmpty();
        assertThat(run(new Script().at(LATER, "A\r"))).contains("A");
        assertThat(run(new Script().at(LATER, "\u001b[B\u001b[B\r"))).contains("n");
        assertThat(run(new Script().at(LATER, "\u001bOA\r"))).contains("A");
        Script s = new Script().at(LATER, "\u001b[B").at(LATER + 500, "\u0003");
        assertThat(run(s)).isEmpty();
        assertThat(s.redraws).isEqualTo(1);
    }

    @Test
    void modified_arrows_function_keys_and_alt_keys_are_ignored_whole() throws Exception {
        String keys = "\u001b[1;5A" + "\u001b[1;3B" + "\u001b[1;2A" + "\u001b[24~" + "\u001bOP" + "\u001b[H"
            + "\u001b[1~" + "\u001b[[A" + "\u001bA" + "\u001by" + "\r";
        Script s = new Script().at(LATER, keys).at(LATER + 500, "\u001b");
        assertThat(run(s)).isEmpty();
        assertThat(s.redraws).isZero();
        assertThat(s.left()).isZero();
    }

    @Test
    void a_bracketed_paste_is_ignored_whole() throws Exception {
        Script s = new Script().at(LATER, "\u001b[200~y\rA\r\u001b[A\r\u001b[201~").at(LATER + 100, "\r")
            .at(LATER + 500, "n\r");
        assertThat(run(s)).contains("n");
        assertThat(s.redraws).isEqualTo(1);
    }

    @Test
    void an_arrow_split_by_a_slow_link_moves_the_highlight_and_answers_nothing() throws Exception {
        Script s = new Script().at(LATER, "\u001b").at(LATER + 120, "[A").at(LATER + 800, "\u001b");
        assertThat(run(s)).isEmpty();
        assertThat(s.redraws).isEqualTo(1);
        assertThat(s.left()).isZero();
    }

    @Test
    void a_lone_escape_cancels_after_the_wait() throws Exception {
        assertThat(run(new Script().at(LATER, "\u001b").at(LATER + 400, "y\r"))).isEmpty();
    }

    /** Types a text one key every {@code gap} ms from {@code start}; returns the time of the last key. */
    private static long typing(Script s, long start, long gap, String text) {
        for (int i = 0; i < text.length(); i++) {
            s.at(start + i * gap, String.valueOf(text.charAt(i)));
        }
        return start + (text.length() - 1) * gap;
    }

    @Test
    void steady_typing_across_the_box_never_answers() throws Exception {
        Script quickly = new Script();
        typing(quickly, 0, 80, "please do it quickly y\r");
        assertThat(run(quickly)).isEmpty();
        Script data = new Script();
        typing(data, 0, 80, "now copy the data\r");
        assertThat(run(data)).isEmpty();
        Script digits = new Script();
        typing(digits, 0, 80, "run step 3\r");
        assertThat(run(digits)).isEmpty();
    }

    @Test
    void after_typing_stops_a_quiet_spell_makes_keys_count() throws Exception {
        Script s = new Script();
        long last = typing(s, 0, 80, "please do it quickly y\r");
        s.at(last + 400, "\u001b[B\r");
        assertThat(run(s)).contains("y");
    }

    @Test
    void a_plain_paste_ending_in_a_choice_does_not_answer() throws Exception {
        assertThat(run(new Script().at(LATER, "copy the data\r").at(LATER + 100, "a\r").at(LATER + 600, "\u001b")))
            .isEmpty();
    }

    @Test
    void the_rest_of_a_split_sequence_after_a_lone_escape_is_discarded() throws Exception {
        Script s = new Script().at(LATER, "\u001b").at(LATER + 250, "[B\r");
        assertThat(run(s)).isEmpty();
        assertThat(s.left()).isZero();
    }

    @Test
    void a_long_bracketed_paste_is_skipped_in_linear_time() throws Exception {
        String body = "x".repeat(200_000);
        Script s = new Script().at(LATER, "\u001b[200~" + body + "\u001b[201~").at(LATER + 600, "n\r");
        long t0 = System.nanoTime();
        assertThat(run(s)).contains("n");
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1000);
    }

    @Test
    void nothing_typed_during_the_box_is_left_for_the_next_reader() throws Exception {
        Script s = new Script().at(0, "Add tests\r").at(LATER, "\u001b[B\r");
        assertThat(run(s)).contains("y");
        assertThat(s.left()).isZero();
    }

    @Test
    void the_box_opens_with_nothing_highlighted() {
        Picker p = Picker.of(QUESTION, CHOICES);
        List<String> box = p.lines(Theme.DARK, Symbols.UNICODE, 80).stream().map(Object::toString).toList();
        assertThat(box).noneMatch(l -> l.contains("❯"));
    }
}
