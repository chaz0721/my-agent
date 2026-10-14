/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeProviderImplTest {

    private static ChatRequest request(String text) {
        ModelRef model = new ModelRef("fake", "fake-model", URI.create("http://fake.invalid"), 1000, 100, Set.of());
        return new ChatRequest(model, Optional.empty(), List.of(UserMessage.text(text)), List.of(),
            ChatOptions.defaults());
    }

    @Test
    void echo_streams_text_and_stamps_model() {
        List<StreamEvent> events = new ArrayList<>();
        AssistantMessage m = FakeProviderImpl.echo().stream(request("hi"), events::add, CancelToken.none());
        assertThat(m.text()).isEqualTo("echo: hi");
        assertThat(m.modelId()).isEqualTo("fake-model");
        assertThat(events).hasSize(3).first().isInstanceOf(StreamEvent.Start.class);
        assertThat(events.get(2)).isEqualTo(new StreamEvent.Done(m));
    }

    @Test
    void scripted_replays_in_order_then_fails() {
        ContentBlock.ToolCall call = new ContentBlock.ToolCall("c", "read", Json.mapper().createObjectNode());
        FakeProviderImpl p =
            FakeProviderImpl.scripted(List.of(FakeProviderImpl.reply(List.of(call), StopReasonEnum.TOOL_USE),
                FakeProviderImpl.reply(List.of(new ContentBlock.Text("done")), StopReasonEnum.STOP)));
        List<StreamEvent> events = new ArrayList<>();
        assertThat(p.stream(request("a"), events::add, CancelToken.none()).toolCalls()).containsExactly(call);
        assertThat(events).extracting(e -> e.getClass().getSimpleName()).containsExactly("Start", "ToolCallStart",
            "ToolCallDelta", "ToolCallEnd", "Done");
        assertThat(p.stream(request("b"), e -> {
        }, CancelToken.none()).text()).isEqualTo("done");
        assertThatThrownBy(() -> p.stream(request("c"), e -> {
        }, CancelToken.none())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelled_token_aborts() {
        CancelTokenSource cancel = new CancelTokenSource();
        cancel.cancel();
        AssistantMessage m = FakeProviderImpl.echo().stream(request("x"), e -> {
        }, cancel);
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.ABORTED);
    }

    @Test
    void cancel_token_runs_callbacks_once_and_late_registrations_immediately() {
        CancelTokenSource cancel = new CancelTokenSource();
        List<String> calls = new ArrayList<>();
        cancel.onCancel(() -> calls.add("early"));
        cancel.cancel();
        cancel.cancel();
        cancel.onCancel(() -> calls.add("late"));
        assertThat(calls).containsExactly("early", "late");
        assertThat(CancelToken.none().isCancelled()).isFalse();
    }

    @Test
    void registry_rejects_duplicates() {
        ProviderRegistry r = new ProviderRegistry();
        r.register(FakeProviderImpl.echo());
        assertThat(r.ids()).containsExactly("fake");
        assertThat(r.get("fake")).isPresent();
        assertThat(r.get("nope")).isEmpty();
        assertThatThrownBy(() -> r.register(FakeProviderImpl.echo())).isInstanceOf(IllegalStateException.class);
    }
}
