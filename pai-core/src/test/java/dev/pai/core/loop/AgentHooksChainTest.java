/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.pai.core.internal.LoopSnapshot;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class AgentHooksChainTest {

    private static final LoopState STATE =
        new LoopSnapshot(List.of(), new ModelRef("p", "m", URI.create("http://x.invalid"), 1, 1, Set.of()), Usage.ZERO,
            0, Path.of("."), false, dev.pai.model.CancelToken.none(), java.util.Optional.empty());
    private static final ContentBlock.ToolCall CALL =
        new ContentBlock.ToolCall("c", "t", Json.mapper().createObjectNode());
    private static final Tool TOOL = new TestTool("t", ToolEffectEnum.READ_ONLY, (a, c) -> ToolResult.text(""));

    @Test
    void before_tool_call_short_circuits_on_first_block() {
        List<String> called = new ArrayList<>();
        AgentHooks chain = AgentHooks.chain(List.of(hook("a", called, ToolDecision.allow()),
            hook("b", called, ToolDecision.block("no")), hook("c", called, ToolDecision.allow())));
        ToolDecision d = chain.beforeToolCall(CALL, TOOL, STATE);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("no");
        assertThat(called).containsExactly("a", "b");
    }

    @Test
    void empty_chain_allows_and_passes_through() {
        AgentHooks chain = AgentHooks.chain(List.of());
        assertThat(chain.beforeToolCall(CALL, TOOL, STATE).allowed()).isTrue();
        ToolResult r = ToolResult.text("x");
        assertThat(chain.afterToolCall(CALL, r, STATE)).isSameAs(r);
        assertThat(chain.steeringMessages()).isEmpty();
    }

    @Test
    void after_and_transform_fold_in_order_and_steering_concatenates() {
        AgentHooks first = new AgentHooks() {
            @Override
            public ToolResult afterToolCall(ContentBlock.ToolCall c, ToolResult r, LoopState s) {
                return ToolResult.text(text(r) + "1");
            }

            @Override
            public List<Message> transformContext(List<Message> m, LoopState s) {
                List<Message> out = new ArrayList<>(m);
                out.add(UserMessage.text("first"));
                return out;
            }

            @Override
            public List<Message> steeringMessages() {
                return List.of(UserMessage.text("s1"));
            }
        };
        AgentHooks second = new AgentHooks() {
            @Override
            public ToolResult afterToolCall(ContentBlock.ToolCall c, ToolResult r, LoopState s) {
                return ToolResult.text(text(r) + "2");
            }

            @Override
            public List<Message> transformContext(List<Message> m, LoopState s) {
                return m.subList(m.size() - 1, m.size());
            }

            @Override
            public List<Message> steeringMessages() {
                return List.of(UserMessage.text("s2"));
            }
        };
        AgentHooks chain = AgentHooks.chain(List.of(first, second));
        assertThat(text(chain.afterToolCall(CALL, ToolResult.text("0"), STATE))).isEqualTo("012");
        List<Message> ctx = chain.transformContext(List.of(UserMessage.text("orig")), STATE);
        assertThat(ctx).hasSize(1);
        assertThat(((ContentBlock.Text)((UserMessage)ctx.get(0)).content().get(0)).text()).isEqualTo("first");
        assertThat(chain.steeringMessages()).hasSize(2);
    }

    private static String text(ToolResult r) {
        return ((ContentBlock.Text)r.content().get(0)).text();
    }

    private static AgentHooks hook(String name, List<String> called, ToolDecision d) {
        return new AgentHooks() {
            @Override
            public ToolDecision beforeToolCall(ContentBlock.ToolCall c, Tool t, LoopState s) {
                called.add(name);
                return d;
            }
        };
    }

    @Test
    void an_after_tool_call_hook_that_throws_is_skipped_and_the_later_ones_still_run() {
        List<String> seen = new ArrayList<>();
        AgentHooks failing = new AgentHooks() {
            @Override
            public ToolResult afterToolCall(ContentBlock.ToolCall c, ToolResult r, LoopState s) {
                throw new IllegalStateException("boom");
            }
        };
        AgentHooks later = new AgentHooks() {
            @Override
            public ToolResult afterToolCall(ContentBlock.ToolCall c, ToolResult r, LoopState s) {
                seen.add(text(r));
                return ToolResult.text(text(r) + "!");
            }
        };
        ToolResult out = AgentHooks.chain(List.of(failing, later)).afterToolCall(CALL, ToolResult.text("x"), STATE);
        assertThat(seen).containsExactly("x");
        assertThat(text(out)).isEqualTo("x!");
    }
}
