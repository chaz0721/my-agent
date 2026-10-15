/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.SessionAccess;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.loop.LoopState;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

/** A host with the sub-agent extension, fake tools, a recorded parent session and a scripted provider. */
final class Harness {

    static final ModelRef MODEL = new ModelRef("fake", "m", URI.create("http://fake.invalid"), 100_000, 1_000,
        Set.of(ModelRef.CapabilityEnum.TOOLS));
    static final String PARENT_SESSION = "parent0001";

    final Path home;
    final Path cwd;
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    final List<SessionEntry.CustomEntry> entries = new CopyOnWriteArrayList<>();
    final List<String> notices = new CopyOnWriteArrayList<>();
    /** Paths written by the fake write tool, as "start:path" / "end:path" in order. */
    final List<String> writes = new CopyOnWriteArrayList<>();
    final ExtensionHost host;

    Harness(Path tmp, Map<String, String> options, Function<ChatRequest, AssistantMessage> responder) throws Exception {
        this.home = Files.createDirectories(tmp.resolve("home"));
        this.cwd = Files.createDirectories(tmp.resolve("proj"));
        Path sessionDir = Files.createDirectories(home.resolve("sessions").resolve("0123456789abcdef"));
        Files.writeString(sessionDir.resolve("20261016-000000-" + PARENT_SESSION + ".jsonl"),
            "{\"type\":\"session\"}\n");
        FakeProviderImpl provider = new FakeProviderImpl("fake", req -> {
            requests.add(req);
            return responder.apply(req);
        });
        SessionAccess session = new SessionAccess() {
            @Override
            public Optional<String> append(String customType, JsonNode data) {
                entries.add(new SessionEntry.CustomEntry("e" + entries.size(), Optional.empty(), Instant.EPOCH,
                    customType, data));
                return Optional.of("e" + entries.size());
            }

            @Override
            public Optional<SessionEntry.CustomEntry> latest(String customType) {
                return Optional.empty();
            }
        };
        ExtensionHost h = new ExtensionLoader().install(List.of(new SubagentExtension(), new FakeTools(this)),
            new ExtensionHost.Settings(home, cwd, true, Optional.empty(), notices::add, options, session)
                .withProvider(() -> Optional.of(provider)));
        this.host = h;
        h.dispatch(new SessionEvent.SessionStarted(new SessionId(PARENT_SESSION), false));
        parentRequest(CancelToken.none());
    }

    /** What the parent's loop does before each request: the extension learns the model and the cancellation. */
    void parentRequest(CancelToken cancel) {
        ChatRequest req = new ChatRequest(MODEL, Optional.of("parent system"), List.of(UserMessage.text("hi")),
            List.of(), ChatOptions.defaults());
        host.hooks().prepareRequest(req, state(cancel));
    }

    static LoopState state(CancelToken cancel) {
        return new LoopState() {
            @Override
            public List<Message> messages() {
                return List.of();
            }

            @Override
            public ModelRef model() {
                return MODEL;
            }

            @Override
            public Usage usageThisRun() {
                return Usage.ZERO;
            }

            @Override
            public int turnIndex() {
                return 0;
            }

            @Override
            public Path cwd() {
                return Path.of(".");
            }

            @Override
            public CancelToken cancel() {
                return cancel;
            }
        };
    }

    /** Calls a tool of the host the way the parent loop would, with no progress consumer. */
    ToolResult call(String tool, String json, CancelToken cancel) throws Exception {
        List<String> progress = new ArrayList<>();
        return host.tools().get(tool).orElseThrow().execute(Json.mapper().readTree(json), new ToolContext() {
            @Override
            public Path cwd() {
                return cwd;
            }

            @Override
            public CancelToken cancel() {
                return cancel;
            }

            @Override
            public void progress(String partial) {
                progress.add(partial);
            }
        });
    }

    static String text(ToolResult r) {
        return ((ContentBlock.Text)r.content().get(0)).text();
    }

    /** The first user text of a request: the task of a sub-agent, "hi" for the parent. */
    static String task(ChatRequest r) {
        return r.messages().get(0) instanceof UserMessage u ? ((ContentBlock.Text)u.content().get(0)).text() : "";
    }

    static AssistantMessage say(String text) {
        return FakeProviderImpl.reply(List.of(new ContentBlock.Text(text)), StopReasonEnum.STOP);
    }

    static AssistantMessage use(String tool, String json) {
        ObjectNode args;
        try {
            args = (ObjectNode)Json.mapper().readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        return FakeProviderImpl.reply(List.of(new ContentBlock.ToolCall("c" + json.hashCode(), tool, args)),
            StopReasonEnum.TOOL_USE);
    }

    /** Fake read, write and bash tools; bash is read-only for {@code ls}. */
    static final class FakeTools implements dev.pai.core.ext.Extension {
        private final Harness h;

        FakeTools(Harness h) {
            this.h = h;
        }

        @Override
        public String id() {
            return "fake-tools";
        }

        @Override
        public void onLoad(dev.pai.core.ext.ExtensionContext ctx) {
            ctx.registerTool(tool("read", ToolEffectEnum.READ_ONLY, a -> {
                try {
                    return Files.readString(h.cwd.resolve(a.path("path").asText()));
                } catch (java.io.IOException e) {
                    return "missing";
                }
            }));
            ctx.registerTool(tool("todo_write", ToolEffectEnum.READ_ONLY, a -> "parent list"));
            ctx.registerTool(tool("write", ToolEffectEnum.WRITE_FS, a -> {
                String p = a.path("path").asText();
                h.writes.add("start:" + p);
                try {
                    Thread.sleep(a.path("millis").asLong(0L));
                    Path f = h.cwd.resolve(p);
                    String old = Files.exists(f) ? Files.readString(f) : "";
                    Files.writeString(f, old + a.path("content").asText());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                h.writes.add("end:" + p);
                return "written";
            }));
            ctx.registerTool(new Tool() {
                @Override
                public ToolDefinition definition() {
                    return new ToolDefinition("bash", "fake", JsonSchema.object().string("command", "c").build());
                }

                @Override
                public ToolEffectEnum effect() {
                    return ToolEffectEnum.EXECUTE;
                }

                @Override
                public ToolEffectEnum effect(JsonNode input) {
                    return input.path("command").asText().startsWith("ls") ? ToolEffectEnum.READ_ONLY : effect();
                }

                @Override
                public ToolResult execute(JsonNode arguments, ToolContext tc) {
                    return ToolResult.text("ran " + arguments.path("command").asText());
                }
            });
        }

        private static Tool tool(String name, ToolEffectEnum effect, Function<JsonNode, String> body) {
            return new Tool() {
                @Override
                public ToolDefinition definition() {
                    return new ToolDefinition(name, "fake", JsonSchema.object().string("path", "p").build());
                }

                @Override
                public ToolEffectEnum effect() {
                    return effect;
                }

                @Override
                public ToolResult execute(JsonNode arguments, ToolContext tc) {
                    return ToolResult.text(body.apply(arguments));
                }
            };
        }
    }
}
