/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.pai.core.internal.LoopSnapshot;
import dev.pai.core.session.SessionTree;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Message;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

import static dev.pai.core.compaction.CompactionPolicyTest.model;
import static dev.pai.core.compaction.CompactionPolicyTest.say;
import static dev.pai.core.compaction.CompactionPolicyTest.user;
import static org.assertj.core.api.Assertions.assertThat;

class ModelSummarizerImplTest {

    @Test
    void an_oversized_prefix_is_cut_from_the_front_but_keeps_the_earlier_summary() {
        List<ChatRequest> requests = new ArrayList<>();
        FakeProviderImpl provider = new FakeProviderImpl("fake", req -> {
            requests.add(req);
            return FakeProviderImpl.reply(List.of(new ContentBlock.Text(SummaryValidatorTest.VALID)),
                StopReasonEnum.STOP);
        });
        List<Message> prefix = new ArrayList<>();
        prefix.add(SessionTree.summaryMessage("OLD-SUMMARY", Instant.EPOCH));
        for (int i = 0; i < 200; i++) {
            prefix.add(user("question " + i + " " + "q".repeat(1000)));
            prefix.add(say("answer " + i + " " + "a".repeat(1000)));
        }
        String summary =
            new ModelSummarizerImpl(() -> provider, new dev.pai.core.tool.ToolRegistry())
                .summarize(prefix,
                    new LoopSnapshot(prefix, model(8_000), Usage.ZERO, 0, Path.of("."), false,
                        dev.pai.model.CancelToken.none(), java.util.Optional.empty()),
                    dev.pai.model.CancelToken.none());
        assertThat(summary).isEqualTo(SummaryValidatorTest.VALID);
        String sent = ((ContentBlock.Text)((UserMessage)requests.get(0).messages().get(0)).content().get(0)).text();
        assertThat(sent).contains("OLD-SUMMARY", "earlier messages omitted", "answer 199")
            .doesNotContain("question 0 ");
        assertThat(sent.length()).isLessThan(ModelSummarizerImpl.MIN_TRANSCRIPT_CHARS + 4_000);
    }

    @Test
    void a_prefix_within_budget_is_sent_whole() {
        String t = ModelSummarizerImpl.transcript(List.of(user("hi"), say("hello")), 1_000);
        assertThat(t).isEqualTo("[user]: hi\n[assistant]: hello");
    }
}
