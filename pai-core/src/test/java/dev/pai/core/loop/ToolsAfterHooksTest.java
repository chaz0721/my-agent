/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool list sent is what the hooks returned plus tools registered while they ran.
 */
class ToolsAfterHooksTest {

    @TempDir
    Path cwd;

    private final ToolRegistry tools = new ToolRegistry();
    private final List<ChatRequest> requests = new ArrayList<>();

    private static TestTool tool(String name) {
        return new TestTool(name, ToolEffectEnum.READ_ONLY, (a, c) -> ToolResult.text("ok"));
    }

    private List<String> sentTools(AgentHooks hooks, Set<ModelRef.CapabilityEnum> caps) {
        ModelRef model = new ModelRef("fake", "m", URI.create("http://fake.invalid"), 1000, 100, caps);
        FakeProviderImpl provider = new FakeProviderImpl("fake", req -> {
            requests.add(req);
            return FakeProviderImpl.reply(List.of(new ContentBlock.Text("done")), StopReasonEnum.STOP);
        });
        AgentConfig cfg = new AgentConfig(model, "sys", ChatOptions.defaults(), 4, 10, false);
        new AgentLoop(provider, tools, hooks, cfg, cwd).run(List.of(), UserMessage.text("go"), e -> {
        }, CancelToken.none());
        return requests.get(0).tools().stream().map(ToolDefinition::name).toList();
    }

    @Test
    void tools_registered_during_prepare_request_are_offered() {
        tools.register(tool("early"), "x");
        AgentHooks late = new AgentHooks() {
            @Override
            public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
                tools.register(tool("late"), "x");
                return request;
            }
        };
        assertThat(sentTools(late, Set.of(ModelRef.CapabilityEnum.TOOLS))).containsExactly("early", "late");
    }

    @Test
    void a_tool_removed_by_a_hook_stays_removed() {
        tools.register(tool("keep"), "x");
        tools.register(tool("hide"), "x");
        AgentHooks hide = new AgentHooks() {
            @Override
            public ChatRequest prepareRequest(ChatRequest r, LoopState state) {
                List<ToolDefinition> kept = r.tools().stream().filter(d -> !"hide".equals(d.name())).toList();
                return new ChatRequest(r.model(), r.systemPrompt(), r.messages(), kept, r.options());
            }
        };
        assertThat(sentTools(hide, Set.of(ModelRef.CapabilityEnum.TOOLS))).containsExactly("keep");
    }

    @Test
    void a_tool_a_hook_already_added_is_not_repeated() {
        AgentHooks addsItself = new AgentHooks() {
            @Override
            public ChatRequest prepareRequest(ChatRequest r, LoopState state) {
                TestTool t = tool("dynamic");
                tools.register(t, "x");
                List<ToolDefinition> with = new ArrayList<>(r.tools());
                with.add(t.definition());
                return new ChatRequest(r.model(), r.systemPrompt(), r.messages(), with, r.options());
            }
        };
        assertThat(sentTools(addsItself, Set.of(ModelRef.CapabilityEnum.TOOLS))).containsExactly("dynamic");
    }

    @Test
    void models_without_tools_get_none_even_when_tools_appear_late() {
        AgentHooks late = new AgentHooks() {
            @Override
            public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
                tools.register(tool("late"), "x");
                return request;
            }
        };
        assertThat(sentTools(late, Set.of())).isEmpty();
    }
}
