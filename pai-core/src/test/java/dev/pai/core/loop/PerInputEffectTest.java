/* pai-next — Apache-2.0 */
package dev.pai.core.loop;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatOptions;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class PerInputEffectTest {

    private static final ModelRef MODEL = new ModelRef("fake", "fake-model", URI.create("http://fake.invalid"), 1000,
        100, Set.of(ModelRef.CapabilityEnum.TOOLS));

    @TempDir
    Path cwd;

    /** An executing tool whose calls on paths starting with {@code r} only read; each waits for a second one. */
    private static class ShellLike implements Tool {
        private final CountDownLatch both = new CountDownLatch(2);

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("sh", "sh", JsonSchema.object().string("path", "p").required("path").build());
        }

        @Override
        public ToolEffectEnum effect() {
            return ToolEffectEnum.EXECUTE;
        }

        @Override
        public ToolEffectEnum effect(JsonNode input) {
            return input.path("path").asText().startsWith("r") ? ToolEffectEnum.READ_ONLY : ToolEffectEnum.EXECUTE;
        }

        /** As the shell tool does: read-only calls are concurrency-safe although the tool is not. */
        @Override
        public boolean concurrencySafe(JsonNode input) {
            return Tool.effectOf(this, input) == ToolEffectEnum.READ_ONLY;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext ctx) throws InterruptedException {
            both.countDown();
            return ToolResult.text("together:" + both.await(300L, TimeUnit.MILLISECONDS));
        }
    }

    /** Read-only, but declares itself unsafe to run in parallel; keeps the default {@code concurrencySafe(input)}. */
    private static final class OptedOut implements Tool {
        private final ShellLike timing = new ShellLike();

        @Override
        public ToolDefinition definition() {
            return timing.definition();
        }

        @Override
        public ToolEffectEnum effect() {
            return ToolEffectEnum.READ_ONLY;
        }

        @Override
        public boolean concurrencySafe() {
            return false;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext ctx) throws InterruptedException {
            return timing.execute(arguments, ctx);
        }
    }

    private static ContentBlock.ToolCall call(String id, String path) {
        return new ContentBlock.ToolCall(id, "sh", Json.mapper().createObjectNode().put("path", path));
    }

    private List<String> run(ContentBlock.ToolCall a, ContentBlock.ToolCall b) {
        return run(new ShellLike(), a, b);
    }

    private List<String> run(Tool tool, ContentBlock.ToolCall a, ContentBlock.ToolCall b) {
        ToolRegistry tools = new ToolRegistry();
        tools.register(tool, "test");
        AgentLoop loop = new AgentLoop(
            FakeProviderImpl.scripted(List.of(FakeProviderImpl.reply(List.of(a, b), StopReasonEnum.TOOL_USE),
                FakeProviderImpl.reply(List.of(new ContentBlock.Text("done")), StopReasonEnum.STOP))),
            tools, new AgentHooks() {}, new AgentConfig(MODEL, "sys", ChatOptions.defaults(), 4, 5), cwd);
        List<Message> added = loop.run(List.of(), UserMessage.text("go"), e -> {
        }, CancelToken.none());
        List<String> out = new ArrayList<>();
        added.stream().filter(ToolResultMessage.class::isInstance).map(ToolResultMessage.class::cast)
            .forEach(r -> out.add(((ContentBlock.Text)r.content().get(0)).text()));
        return out;
    }

    @Test
    @Timeout(10)
    void calls_that_only_read_for_their_input_run_in_parallel() {
        assertThat(run(call("1", "r1"), call("2", "r2"))).containsExactly("together:true", "together:true");
    }

    @Test
    @Timeout(10)
    void a_tool_whose_per_input_check_throws_runs_alone() {
        ShellLike throwing = new ShellLike() {
            @Override
            public boolean concurrencySafe(JsonNode input) {
                throw new IllegalStateException("broken");
            }
        };
        assertThat(run(throwing, call("1", "r1"), call("2", "r2"))).containsExactly("together:false", "together:true");
    }

    @Test
    @Timeout(10)
    void a_read_only_tool_that_opts_out_of_concurrency_is_not_run_in_parallel() {
        Tool optedOut = new OptedOut();
        assertThat(run(optedOut, call("1", "r1"), call("2", "r2"))).containsExactly("together:false", "together:true");
    }

    @Test
    @Timeout(10)
    void one_executing_call_makes_the_batch_sequential() {
        assertThat(run(call("1", "r1"), call("2", "x"))).containsExactly("together:false", "together:true");
    }

    private static Tool tool(ToolEffectEnum declared, Function<JsonNode, ToolEffectEnum> refined) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("t", "t", JsonSchema.object().build());
            }

            @Override
            public ToolEffectEnum effect() {
                return declared;
            }

            @Override
            public ToolEffectEnum effect(JsonNode input) {
                return refined.apply(input);
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolContext ctx) {
                return ToolResult.text("x");
            }
        };
    }

    @Test
    void per_input_effects_may_only_be_safer_and_fail_closed() {
        JsonNode in = Json.mapper().createObjectNode();
        assertThat(Tool.effectOf(tool(ToolEffectEnum.EXECUTE, i -> ToolEffectEnum.READ_ONLY), in))
            .isEqualTo(ToolEffectEnum.READ_ONLY);
        assertThat(Tool.effectOf(tool(ToolEffectEnum.UNKNOWN, i -> ToolEffectEnum.READ_ONLY), in))
            .isEqualTo(ToolEffectEnum.READ_ONLY);
        assertThat(Tool.effectOf(tool(ToolEffectEnum.READ_ONLY, i -> ToolEffectEnum.EXECUTE), in))
            .as("more dangerous than declared: the more dangerous one").isEqualTo(ToolEffectEnum.EXECUTE);
        assertThat(Tool.effectOf(tool(ToolEffectEnum.READ_ONLY, i -> null), in)).isEqualTo(ToolEffectEnum.UNKNOWN);
        assertThat(Tool.effectOf(tool(ToolEffectEnum.EXECUTE, i -> ToolEffectEnum.WRITE_FS), in))
            .isEqualTo(ToolEffectEnum.EXECUTE);
        assertThat(Tool.effectOf(tool(ToolEffectEnum.EXECUTE, i -> null), in)).isEqualTo(ToolEffectEnum.EXECUTE);
        Tool broken = tool(ToolEffectEnum.WRITE_FS, i -> {
            throw new IllegalStateException("broken");
        });
        assertThat(Tool.effectOf(broken, in)).isEqualTo(ToolEffectEnum.WRITE_FS);
        assertThat(tool(ToolEffectEnum.UNKNOWN, i -> ToolEffectEnum.UNKNOWN).concurrencySafe(in)).isFalse();
        assertThat(tool(ToolEffectEnum.READ_ONLY, i -> ToolEffectEnum.READ_ONLY).concurrencySafe(in)).isTrue();
    }
}
