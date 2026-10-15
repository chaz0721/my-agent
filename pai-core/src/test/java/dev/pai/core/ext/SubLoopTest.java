/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.loop.AgentConfig;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.Provider;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubLoopTest {

    private static final ModelRef MODEL =
        new ModelRef("fake", "m", URI.create("http://fake.invalid"), 1000, 100, Set.of(ModelRef.CapabilityEnum.TOOLS));

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "test", JsonSchema.object().build());
            }

            @Override
            public ToolEffectEnum effect() {
                return ToolEffectEnum.READ_ONLY;
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolContext ctx) {
                return ToolResult.text("ran " + name);
            }
        };
    }

    /** Registers tools a, b and c plus hooks that block c, mark results and touch requests, steering and context. */
    private static Extension probe(AtomicReference<ExtensionContext> ref) {
        return new Extension() {
            @Override
            public String id() {
                return "probe";
            }

            @Override
            public void onLoad(ExtensionContext ctx) {
                ref.set(ctx);
                List.of("a", "b", "c").forEach(n -> ctx.registerTool(tool(n)));
                ctx.addHooks(new AgentHooks() {
                    @Override
                    public ToolDecision beforeToolCall(ContentBlock.ToolCall call, Tool tool, LoopState state) {
                        return "c".equals(call.name()) ? ToolDecision.block("no c") : ToolDecision.allow();
                    }

                    @Override
                    public ToolResult afterToolCall(ContentBlock.ToolCall call, ToolResult result, LoopState s) {
                        return ToolResult.text("after:" + ((ContentBlock.Text)result.content().get(0)).text());
                    }

                    @Override
                    public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
                        return new ChatRequest(request.model(), Optional.of("injected"), request.messages(),
                            request.tools(), request.options());
                    }

                    @Override
                    public List<Message> transformContext(List<Message> messages, LoopState state) {
                        return List.of(UserMessage.text("replaced"));
                    }

                    @Override
                    public List<Message> steeringMessages() {
                        return List.of(UserMessage.text("steer"));
                    }
                });
            }
        };
    }

    private static ExtensionContext install(Optional<Provider> provider) {
        AtomicReference<ExtensionContext> ref = new AtomicReference<>();
        new ExtensionLoader().install(List.of(probe(ref)),
            ExtensionHost.Settings.of(Path.of("h"), Path.of("c")).withProvider(() -> provider));
        return ref.get();
    }

    private static ContentBlock.ToolCall call(String id, String name) {
        return new ContentBlock.ToolCall(id, name, Json.mapper().createObjectNode());
    }

    @Test
    void without_a_provider_no_sub_loop_can_be_made() {
        ExtensionContext ctx = install(Optional.empty());
        assertThatThrownBy(() -> ctx.subLoop(AgentConfig.of(MODEL, "sys"), t -> true))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("no provider");
    }

    @Test
    void a_sub_loop_gets_the_offered_tools_and_only_the_tool_call_hooks() {
        List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        List<AssistantMessage> script =
            List.of(FakeProviderImpl.reply(List.of(call("1", "a"), call("2", "c")), StopReasonEnum.TOOL_USE),
                FakeProviderImpl.reply(List.of(new ContentBlock.Text("done")), StopReasonEnum.STOP));
        FakeProviderImpl scripted = FakeProviderImpl.scripted(script);
        Provider recording = new FakeProviderImpl("fake", req -> {
            requests.add(req);
            return scripted.stream(req, ev -> {
            }, CancelToken.none());
        });
        ExtensionContext ctx = install(Optional.of(recording));
        List<Message> added = ctx.subLoop(AgentConfig.of(MODEL, "sys"), t -> !"b".equals(t.definition().name()))
            .run(List.of(), UserMessage.text("go"), ev -> {
            }, CancelToken.none());

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).tools()).extracting(ToolDefinition::name).containsExactly("a", "c");
        assertThat(requests).allSatisfy(r -> assertThat(r.systemPrompt()).contains("sys"));
        assertThat(requests.get(0).messages()).containsExactly(added.get(0));
        List<String> results = added.stream().filter(ToolResultMessage.class::isInstance)
            .map(m -> ((ContentBlock.Text)((ToolResultMessage)m).content().get(0)).text()).toList();
        assertThat(results).containsExactly("after:ran a", "after:tool call blocked: no c");
        assertThat(added)
            .noneMatch(m -> m instanceof UserMessage u && u.content().contains(new ContentBlock.Text("steer")));
    }
}
