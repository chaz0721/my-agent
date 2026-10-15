/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecallTest {

    private static MemoryEntry entry(String id, String text, long seconds) {
        return new MemoryEntry(id, text, List.of(), Instant.ofEpochSecond(seconds), Optional.empty(), "global");
    }

    private static final List<MemoryEntry> ENTRIES =
        List.of(entry("a", "The user prefers tabs over spaces", 1), entry("b", "Run tests with mvn -q verify", 2),
            entry("c", "\u7528\u6237\u559c\u6b22\u7b80\u6d01\u7684\u4e2d\u6587\u56de\u7b54", 3),
            entry("d", "Deploy target is the staging cluster", 4));

    @Test
    void ranks_by_shared_terms() {
        assertThat(Recall.rank(ENTRIES, "how do I run the tests?", 5)).extracting(h -> h.entry().id()).first()
            .isEqualTo("b");
        assertThat(Recall.rank(ENTRIES, "tabs or spaces", 5)).extracting(h -> h.entry().id()).containsExactly("a");
    }

    @Test
    void chinese_text_matches_by_characters_and_pairs() {
        assertThat(Recall.rank(ENTRIES, "\u4e2d\u6587\u56de\u7b54", 5)).extracting(h -> h.entry().id())
            .containsExactly("c");
        assertThat(Recall.tokens("\u4e2d\u6587ab")).containsExactly("\u4e2d", "\u6587", "\u4e2d\u6587", "ab");
    }

    @Test
    void no_terms_or_no_overlap_give_nothing_and_limit_applies() {
        assertThat(Recall.rank(ENTRIES, "   ", 5)).isEmpty();
        assertThat(Recall.rank(ENTRIES, "quantum chromodynamics", 5)).isEmpty();
        assertThat(Recall.rank(ENTRIES, "the", 1)).hasSize(1);
    }

    @Test
    void ties_go_to_the_newer_memory() {
        List<MemoryEntry> twins = List.of(entry("old", "alpha beta", 1), entry("new", "alpha beta", 9));
        assertThat(Recall.rank(twins, "alpha", 2)).extracting(h -> h.entry().id()).containsExactly("new", "old");
    }
}
