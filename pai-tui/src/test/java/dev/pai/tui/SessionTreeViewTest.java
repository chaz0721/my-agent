/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionTree;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTreeViewTest {

    private static SessionEntry msg(String id, String parent, String text) {
        return new SessionEntry.MessageEntry(id, Optional.ofNullable(parent), Instant.EPOCH, UserMessage.text(text));
    }

    @Test
    void chains_stay_flat_forks_indent_and_leaf_is_marked() {
        SessionTree tree = SessionTree.of(List.of(msg("aaaaaaaa", null, "root"), msg("bbbbbbbb", "aaaaaaaa", "left"),
            msg("cccccccc", "bbbbbbbb", "left 2"), msg("dddddddd", "aaaaaaaa", "right " + "r".repeat(100)),
            new SessionEntry.CompactionEntry("eeeeeeee", Optional.of("dddddddd"), Instant.EPOCH, "sum", "dddddddd",
                900L),
            new SessionEntry.LabelEntry("ffffffff", Optional.of("eeeeeeee"), Instant.EPOCH, "bbbbbbbb", "checkpoint")));
        String out = SessionTreeView.render(tree, Optional.of("ffffffff"));
        assertThat(out.lines().toList()).containsExactly("* aaaaaa user: root",
            "  +-   bbbbbb user: left  [checkpoint]", "       cccccc user: left 2",
            "  +- * dddddd user: right " + "r".repeat(51) + "...",
            "     * eeeeee compaction (900 tokens, kept from dddddd): sum",
            "     * ffffff label bbbbbb: checkpoint  <- leaf");
        assertThat(SessionTreeView.render(SessionTree.of(List.of()), Optional.empty())).isEqualTo("(empty session)");
    }
}
