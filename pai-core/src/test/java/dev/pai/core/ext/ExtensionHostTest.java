/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.session.SessionId;
import dev.pai.core.tool.Tool;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionHostTest {

    @Test
    void extensions_register_through_their_context() {
        List<String> seen = new ArrayList<>();
        Extension ext = new Extension() {
            @Override
            public String id() {
                return "demo";
            }

            @Override
            public void onLoad(ExtensionContext ctx) {
                ctx.registerProvider(FakeProviderImpl.echo());
                ctx.registerCommand(new SlashCommand() {
                    @Override
                    public String name() {
                        return "hello";
                    }

                    @Override
                    public String description() {
                        return "says hello";
                    }

                    @Override
                    public CommandResult run(String args, CommandContext c) {
                        return new CommandResult("hello " + args, false);
                    }
                });
                ctx.addHooks(new AgentHooks() {
                    @Override
                    public ToolDecision beforeToolCall(ContentBlock.ToolCall c, Tool t, LoopState s) {
                        return ToolDecision.block("demo says no");
                    }
                });
                ctx.on(AgentEvent.TurnStart.class, e -> seen.add("turn"));
                ctx.on(AgentEvent.AgentStart.class, e -> {
                    throw new IllegalStateException("listener bug");
                });
                ctx.onSession(SessionEvent.SessionStarted.class, e -> seen.add("session"));
                seen.add(ctx.configDir().toString());
                seen.add(ctx.logger() == null ? "no logger" : "logger");
            }

            @Override
            public void onUnload() {
                seen.add("unloaded");
            }
        };
        ExtensionHost host = new ExtensionLoader().install(List.of(ext), Path.of("home"));
        assertThat(host.providers().ids()).containsExactly("fake");
        assertThat(host.command("hello").orElseThrow().run("you", null).output()).isEqualTo("hello you");
        ContentBlock.ToolCall call = new ContentBlock.ToolCall("c", "x", Json.mapper().createObjectNode());
        assertThat(host.hooks().beforeToolCall(call, null, null).reason()).contains("demo says no");
        host.dispatch(new AgentEvent.AgentStart());
        host.dispatch(new AgentEvent.TurnStart(0));
        host.dispatch(new AgentEvent.Error("x", Optional.empty()));
        host.dispatch(new SessionEvent.SessionStarted(new SessionId("s1"), false));
        host.unloadAll();
        assertThat(seen).containsExactly(Path.of("home", "ext", "demo").toString(), "logger", "turn", "session",
            "unloaded");
    }

    @Test
    void service_loader_finds_nothing_in_core_alone() {
        assertThat(new ExtensionLoader().loadFromServiceLoader()).isEmpty();
    }
}
