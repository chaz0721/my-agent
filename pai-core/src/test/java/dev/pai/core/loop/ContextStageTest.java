/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.tool.ToolRegistry;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class ContextStageTest {

    private static final ModelRef MODEL = new ModelRef("fake", "fake-model", URI.create("http://fake.invalid"), 1000,
        100, Set.of(ModelRef.CapabilityEnum.TOOLS));

    @TempDir
    Path cwd;

    private final List<String> calls = new ArrayList<>();
    private final List<AgentEvent> events = new ArrayList<>();
    private final List<ChatRequest> requests = new ArrayList<>();
    private final List<ChatRequest> sent = new ArrayList<>();

    /** A stage that records each call and applies {@code body}. */
    private final class FakeStage implements ContextStage {
        private final String name;
        private final AgentEvent.ContextCompacted.KindEnum kind;
        private final BiFunction<List<Message>, LoopState, List<Message>> body;

        private FakeStage(String name, AgentEvent.ContextCompacted.KindEnum kind,
            BiFunction<List<Message>, LoopState, List<Message>> body) {
            this.name = name;
            this.kind = kind;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<Message> apply(List<Message> messages, LoopState state) {
            calls.add(name + ":" + messages.size() + "/" + state.messages().size());
            return body.apply(messages, state);
        }

        @Override
        public AgentEvent.ContextCompacted.KindEnum kind() {
            return kind;
        }

        @Override
        public Optional<String> summaryOf(List<Message> result) {
            return kind == AgentEvent.ContextCompacted.KindEnum.SUMMARY ? Optional.of("sum") : Optional.empty();
        }

        @Override
        public void requestSent(ChatRequest request) {
            sent.add(request);
        }
    }

    private static AssistantMessage text(String s) {
        return FakeProviderImpl.reply(List.of(new ContentBlock.Text(s)), StopReasonEnum.STOP);
    }

    private void run(List<ContextStage> stages, int prompts) {
        List<AssistantMessage> script = new ArrayList<>();
        for (int i = 0; i < prompts; i++) {
            script.add(text("reply " + i));
        }
        FakeProviderImpl provider = new FakeProviderImpl("fake", req -> {
            requests.add(req);
            return script.remove(0);
        });
        AgentConfig cfg = AgentConfig.of(MODEL, "sys").withStages(stages);
        List<Message> history = new ArrayList<>(List.of(UserMessage.text("old"), text("old reply")));
        for (int i = 0; i < prompts; i++) {
            history.addAll(new AgentLoop(provider, new ToolRegistry(), new AgentHooks() {}, cfg, cwd).run(history,
                UserMessage.text("go " + i), events::add, CancelToken.none()));
        }
    }

    private List<AgentEvent.ContextCompacted> compactions() {
        return events.stream().filter(AgentEvent.ContextCompacted.class::isInstance)
            .map(AgentEvent.ContextCompacted.class::cast).toList();
    }

    @Test
    void stages_run_in_order_on_the_previous_output_and_a_projection_is_only_sent() {
        ContextStage project = new FakeStage("micro", AgentEvent.ContextCompacted.KindEnum.MICRO,
            (m, s) -> new ArrayList<>(m.subList(1, m.size())));
        ContextStage noop = new FakeStage("summary", AgentEvent.ContextCompacted.KindEnum.SUMMARY, (m, s) -> m);
        run(List.of(project, noop), 1);
        assertThat(calls).containsExactly("micro:3/3", "summary:2/3");
        assertThat(requests.get(0).messages()).hasSize(2);
        assertThat(compactions()).containsExactly(
            new AgentEvent.ContextCompacted(3, 2, AgentEvent.ContextCompacted.KindEnum.MICRO, Optional.empty()));
        assertThat(sent).as("every stage is told").containsExactly(requests.get(0), requests.get(0));
    }

    @Test
    void a_projection_does_not_replace_the_kept_context() {
        ContextStage project = new FakeStage("micro", AgentEvent.ContextCompacted.KindEnum.MICRO,
            (m, s) -> new ArrayList<>(m.subList(1, m.size())));
        run(List.of(project), 2);
        assertThat(calls).containsExactly("micro:3/3", "micro:5/5");
    }

    @Test
    void a_summary_replaces_the_kept_context_and_reports_its_text_and_later_stages_see_it() {
        ContextStage summary = new FakeStage("summary", AgentEvent.ContextCompacted.KindEnum.SUMMARY,
            (m, s) -> s.messages().size() > 2 ? new ArrayList<>(m.subList(2, m.size())) : m);
        ContextStage after = new FakeStage("after", AgentEvent.ContextCompacted.KindEnum.MICRO, (m, s) -> m);
        run(List.of(summary, after), 1);
        assertThat(calls).containsExactly("summary:3/3", "after:1/1");
        assertThat(compactions()).containsExactly(
            new AgentEvent.ContextCompacted(3, 1, AgentEvent.ContextCompacted.KindEnum.SUMMARY, Optional.of("sum")));
        assertThat(events).filteredOn(AgentEvent.AgentEnd.class::isInstance).singleElement().isInstanceOfSatisfying(
            AgentEvent.AgentEnd.class, e -> assertThat(e.reason()).isEqualTo(TerminationReasonEnum.COMPLETED));
    }

    @Test
    void a_failing_stage_is_skipped() {
        ContextStage broken = new FakeStage("broken", AgentEvent.ContextCompacted.KindEnum.SUMMARY, (m, s) -> {
            throw new IllegalStateException("bug");
        });
        ContextStage noop = new FakeStage("noop", AgentEvent.ContextCompacted.KindEnum.SUMMARY, (m, s) -> m);
        run(List.of(broken, noop), 1);
        assertThat(calls).containsExactly("broken:3/3", "noop:3/3");
        assertThat(compactions()).isEmpty();
        assertThat(requests).hasSize(1);
    }
}
