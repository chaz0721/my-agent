/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.ToolResultMessage;

import static dev.pai.core.compaction.CompactionPolicyTest.call;
import static dev.pai.core.compaction.CompactionPolicyTest.say;
import static dev.pai.core.compaction.CompactionPolicyTest.user;
import static org.assertj.core.api.Assertions.assertThat;

class MicroCompactorTest {

    private static final MicroCompactionPolicy POLICY = new MicroCompactionPolicy(2, 100, Set.of());
    private final MicroCompactor micro = new MicroCompactor();

    static ToolResultMessage big(String id, String tool, int lines) {
        String text = String.join("\n", java.util.Collections.nCopies(lines, "x".repeat(49)));
        return new ToolResultMessage(id, tool, List.of(new ContentBlock.Text(text)), true, Instant.EPOCH);
    }

    /** u, then four turns each calling one tool with a large result, then a final reply. */
    private static List<Message> conversation() {
        List<Message> m = new ArrayList<>();
        m.add(user("go"));
        for (int i = 1; i <= 4; i++) {
            m.add(call("c" + i));
            m.add(big("c" + i, "read", 10));
        }
        m.add(say("done"));
        return m;
    }

    @Test
    void early_large_results_are_replaced_and_recent_turns_are_kept() {
        List<Message> in = conversation();
        List<Message> out = micro.apply(in, POLICY);
        assertThat(out).isNotSameAs(in).hasSize(in.size());
        ToolResultMessage first = (ToolResultMessage)out.get(2);
        assertThat(first.content()).containsExactly(
            new ContentBlock.Text("[tool result trimmed by pai: read, 499 chars, 10 lines; call id c1]"));
        assertThat(first.toolCallId()).isEqualTo("c1");
        assertThat(first.toolName()).isEqualTo("read");
        assertThat(first.isError()).isTrue();
        assertThat(out.get(4)).isNotEqualTo(in.get(4));
        assertThat(out.get(6)).isNotEqualTo(in.get(6));
        assertThat(out.get(8)).isSameAs(in.get(8));
        assertThat(out.get(7)).isSameAs(in.get(7));
    }

    @Test
    void every_tool_call_keeps_its_result() {
        List<Message> out = micro.apply(conversation(), POLICY);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) instanceof AssistantMessage a && !a.toolCalls().isEmpty()) {
                assertThat(((ToolResultMessage)out.get(i + 1)).toolCallId()).isEqualTo(a.toolCalls().get(0).id());
            }
        }
    }

    @Test
    void applying_twice_returns_the_same_instance() {
        List<Message> once = micro.apply(conversation(), POLICY);
        assertThat(micro.apply(once, POLICY)).isSameAs(once);
        assertThat(micro.trimmable(once, POLICY)).isZero();
    }

    @Test
    void nothing_to_trim_returns_the_input() {
        List<Message> in = List.of(user("hi"), call("c1"), big("c1", "read", 1), say("ok"));
        assertThat(micro.apply(in, POLICY)).isSameAs(in);
        assertThat(micro.apply(conversation().subList(0, 5), POLICY)).hasSize(5);
    }

    @Test
    void images_in_early_results_are_always_replaced() {
        ToolResultMessage img = new ToolResultMessage("c1", "read",
            List.of(new ContentBlock.Text("a.png"), new ContentBlock.Image("AAAA", "image/png")), false, Instant.EPOCH);
        List<Message> in = List.of(user("look"), call("c1"), img, call("c2"), big("c2", "read", 1), call("c3"),
            big("c3", "read", 1), say("ok"));
        ToolResultMessage out = (ToolResultMessage)micro.apply(in, POLICY).get(2);
        assertThat(out.content())
            .containsExactly(new ContentBlock.Text("[tool result trimmed by pai: read, 5 chars, 1 lines; call id c1]"));
    }

    @Test
    void never_trim_tools_are_kept() {
        List<Message> in = conversation();
        MicroCompactionPolicy keepRead = new MicroCompactionPolicy(2, 100, Set.of("read"));
        assertThat(micro.apply(in, keepRead)).isSameAs(in);
    }
}
