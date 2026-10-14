/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionPolicyTest {

    static ModelRef model(int window) {
        return new ModelRef("fake", "m", URI.create("http://fake.invalid"), window, 1000,
            Set.of(ModelRef.CapabilityEnum.TOOLS));
    }

    static UserMessage user(String s) {
        return UserMessage.text(s);
    }

    static AssistantMessage say(String s) {
        return FakeProviderImpl.reply(List.of(new ContentBlock.Text(s)), StopReasonEnum.STOP);
    }

    static AssistantMessage call(String id) {
        return FakeProviderImpl.reply(
            List.of(new ContentBlock.ToolCall(id, "read", Json.mapper().createObjectNode().put("path", "x"))),
            StopReasonEnum.TOOL_USE);
    }

    static ToolResultMessage result(String id) {
        return new ToolResultMessage(id, "read", List.of(new ContentBlock.Text("content")), false,
            java.time.Instant.EPOCH);
    }

    @Test
    void threshold_is_window_minus_reserve_and_margin_but_at_least_half() {
        CompactionPolicy p = CompactionPolicy.defaults();
        assertThat(p.thresholdFor(model(200_000))).isEqualTo(200_000 - 20_000 - 13_000);
        assertThat(p.thresholdFor(model(8_000))).isEqualTo(4_000);
        assertThat(new CompactionPolicy(0, 0, 2).thresholdFor(model(1000))).isEqualTo(1000);
    }

    @Test
    void split_keeps_recent_messages_and_lands_on_an_exchange_start() {
        List<Message> m = List.of(user("u1"), say("a1"), user("u2"), call("c1"), result("c1"), say("a2"), user("u3"),
            call("c2"), result("c2"), say("a3"));
        CompactionPolicy p = new CompactionPolicy(0, 0, 3);
        int k = p.splitIndex(m, false);
        assertThat(k).isEqualTo(6);
        assertThat(m.get(k)).isInstanceOf(UserMessage.class);
        assertThat(new CompactionPolicy(0, 0, 5).splitIndex(m, false)).isEqualTo(5);
    }

    @Test
    void split_never_separates_a_tool_call_from_its_result() {
        List<Message> m = List.of(user("u1"), call("c1"), result("c1"), call("c2"), result("c2"), call("c3"),
            result("c3"), say("done"));
        for (int keep = 0; keep < m.size(); keep++) {
            int k = new CompactionPolicy(0, 0, keep).splitIndex(m, false);
            if (k > 0) {
                assertThat(m.get(k)).isNotInstanceOf(ToolResultMessage.class);
                assertThat(m.get(k) instanceof AssistantMessage a && !a.toolCalls().isEmpty()).isFalse();
                assertThat(m.size() - k).isGreaterThanOrEqualTo(keep);
            }
        }
        assertThat(new CompactionPolicy(0, 0, 1).splitIndex(m, false)).isEqualTo(7);
        assertThat(new CompactionPolicy(0, 0, 2).splitIndex(m, false)).isZero();
    }

    @Test
    void too_short_contexts_are_not_split_unless_forced() {
        List<Message> m = List.of(user("u1"), say("a1"), user("u2"), say("a2"));
        CompactionPolicy p = CompactionPolicy.defaults();
        assertThat(p.splitIndex(m, false)).isZero();
        assertThat(p.splitIndex(m, true)).isEqualTo(1);
        assertThat(p.splitIndex(List.of(user("only")), true)).isZero();
    }

    @Test
    void estimator_uses_reported_usage_of_the_last_reply_when_larger() {
        CharsTokenEstimatorImpl est = new CharsTokenEstimatorImpl();
        assertThat(est.estimate(List.of(user("x".repeat(400))), List.of(), "")).isEqualTo(100);
        AssistantMessage measured = new AssistantMessage(List.of(new ContentBlock.Text("ok")), "p", "m",
            new Usage(5_000L, 20L, 1_000L, 0L), StopReasonEnum.STOP, Optional.empty(), java.time.Instant.EPOCH);
        assertThat(est.estimate(List.of(user("hi"), measured, user("y".repeat(40))), List.of(), ""))
            .isEqualTo(6_020 + 10);
        assertThat(est.estimate(List.of(), List.of(), "s".repeat(80))).isEqualTo(20);
    }
}
