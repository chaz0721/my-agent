/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import dev.pai.model.codec.StreamDecoder;
import dev.pai.model.codec.anthropic.AnthropicMessagesCodec;
import dev.pai.model.codec.openai.OpenAiCompletionsCodec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the {@link StreamEvent} protocol on every recorded or handwritten fixture of both codecs.
 */
class StreamProtocolTest {

    static Stream<Arguments> fixtures() {
        List<Arguments> out = new ArrayList<>();
        for (String f : Fixtures.list("openai-compat")) {
            StreamDecoder d = new OpenAiCompletionsCodec("openai-compat", true).newDecoder();
            out.add(Arguments.of("openai-compat/" + f,
                Fixtures.replay("/fixtures/openai-compat/" + f, d::feed, d::finish)));
        }
        for (String f : Fixtures.list("anthropic")) {
            StreamDecoder d = new AnthropicMessagesCodec("anthropic").newDecoder();
            out.add(Arguments.of("anthropic/" + f, Fixtures.replay("/fixtures/anthropic/" + f, d::feed, d::finish)));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void start_first_single_terminal_last(String name, List<StreamEvent> events) {
        assertThat(events).as(name).isNotEmpty();
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Start.class);
        assertThat(events.stream().filter(StreamEvent.Start.class::isInstance)).hasSize(1);
        long terminals =
            events.stream().filter(e -> e instanceof StreamEvent.Done || e instanceof StreamEvent.Error).count();
        assertThat(terminals).isEqualTo(1L);
        StreamEvent last = events.get(events.size() - 1);
        assertThat(last instanceof StreamEvent.Done || last instanceof StreamEvent.Error).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void block_indices_open_in_order_and_close_once(String name, List<StreamEvent> events) {
        int highest = -1;
        Set<Integer> seen = new HashSet<>();
        Set<Integer> ended = new HashSet<>();
        for (StreamEvent e : events) {
            int idx = indexOf(e);
            if (idx < 0) {
                continue;
            }
            assertThat(ended).as("%s: event after ToolCallEnd of block %d", name, idx).doesNotContain(idx);
            if (seen.add(idx)) {
                assertThat(idx).as("%s: block %d opened out of order", name, idx).isGreaterThan(highest);
                highest = idx;
            }
            if (e instanceof StreamEvent.ToolCallEnd end) {
                ended.add(idx);
                assertThat(end.toolCall().arguments().isObject()).isTrue();
            }
        }
        AssistantMessage message = Fixtures.terminal(events);
        assertThat(message.content().size()).isGreaterThan(highest);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void accumulated_text_and_thinking_deltas_equal_the_final_blocks(String name, List<StreamEvent> events) {
        Map<Integer, StringBuilder> text = new TreeMap<>();
        Map<Integer, StringBuilder> thinking = new TreeMap<>();
        for (StreamEvent e : events) {
            if (e instanceof StreamEvent.TextDelta t) {
                text.computeIfAbsent(t.index(), i -> new StringBuilder()).append(t.delta());
            } else if (e instanceof StreamEvent.ThinkingDelta t) {
                thinking.computeIfAbsent(t.index(), i -> new StringBuilder()).append(t.delta());
            }
        }
        AssistantMessage message = Fixtures.terminal(events);
        Set<Integer> both = new HashSet<>(text.keySet());
        both.retainAll(thinking.keySet());
        assertThat(both).as("%s: index used for both text and thinking", name).isEmpty();
        text.forEach((i, sb) -> {
            assertThat(message.content()).as("%s: block %d missing", name, i).hasSizeGreaterThan(i);
            assertThat(message.content().get(i)).as("%s: block %d type", name, i).isInstanceOf(ContentBlock.Text.class);
            assertThat(((ContentBlock.Text)message.content().get(i)).text()).as("%s: block %d text", name, i)
                .isEqualTo(sb.toString());
        });
        thinking.forEach((i, sb) -> {
            assertThat(message.content()).as("%s: block %d missing", name, i).hasSizeGreaterThan(i);
            assertThat(message.content().get(i)).as("%s: block %d type", name, i)
                .isInstanceOf(ContentBlock.Thinking.class);
            assertThat(((ContentBlock.Thinking)message.content().get(i)).text()).as("%s: block %d thinking", name, i)
                .isEqualTo(sb.toString());
        });
    }

    private static int indexOf(StreamEvent e) {
        return switch (e) {
            case StreamEvent.TextDelta t -> t.index();
            case StreamEvent.ThinkingDelta t -> t.index();
            case StreamEvent.ToolCallStart t -> t.index();
            case StreamEvent.ToolCallDelta t -> t.index();
            case StreamEvent.ToolCallEnd t -> t.index();
            case StreamEvent.Start s -> -1;
            case StreamEvent.Done d -> -1;
            case StreamEvent.Error x -> -1;
        };
    }
}
