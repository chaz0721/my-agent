/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.compaction.CharsTokenEstimatorImpl;
import dev.pai.core.compaction.CompactingHooks;
import dev.pai.core.compaction.CompactionPolicy;
import dev.pai.core.compaction.ModelSummarizerImpl;
import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.loop.AgentConfig;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
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
import dev.pai.model.StreamEvent;
import dev.pai.model.ToolResultMessage;
import dev.pai.tui.PlainRendererImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REPL with the real task list extension and the scripted fake provider: where the tasks entry lands in the
 * session, that compaction still lines up with the session, and that a resumed REPL sees the list.
 */
class ReplTasksTest {

    private static final ModelRef MODEL = new ModelRef(FakeProviderImpl.FAKE_ID, FakeProviderImpl.FAKE_ID,
        URI.create("http://fake.invalid"), 100_000, 1000, Set.of(ModelRef.CapabilityEnum.TOOLS));

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private Repl repl(SessionStore store, SessionId id, Provider provider, String input) {
        AtomicReference<SessionId> session = new AtomicReference<>(id);
        AtomicReference<Repl> ref = new AtomicReference<>();
        SessionEntriesImpl entries =
            new SessionEntriesImpl(store, () -> Optional.ofNullable(ref.get()).map(Repl::cursor), Clock.systemUTC());
        ExtensionLoader loader = new ExtensionLoader();
        ExtensionHost host =
            loader.install(loader.loadFromServiceLoader(), ExtensionHost.Settings.of(home, cwd).withSession(entries));
        CompactingHooks compacting = new CompactingHooks(new CharsTokenEstimatorImpl(), new CompactionPolicy(100, 0, 1),
            new ModelSummarizerImpl(() -> provider), store, new CompactingHooks.Target(session::get));
        Repl repl = new Repl(new Repl.Setup(host, store, session, AgentConfig.of(MODEL, "system"), compacting,
            ModelCatalog.load(home.resolve("models.json")), EnvConfig.load(Map.of(), cwd, home, true),
            new ProviderSetup.Wiring(false, m -> {
            }), cwd, home), new ProviderSetup.Selected(provider, MODEL),
            new PlainRendererImpl(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8, new PrintStream(out, true, StandardCharsets.UTF_8)));
        ref.set(repl);
        return repl;
    }

    private static AssistantMessage todoCall() {
        return FakeProviderImpl.reply(
            List.of(new ContentBlock.ToolCall("t1", "todo_write",
                Json.mapper().createObjectNode().set("items", Json.mapper().createArrayNode().add(Json.mapper()
                    .createObjectNode().put("id", "1").put("content", "Do A").put("status", "in_progress"))))),
            StopReasonEnum.TOOL_USE);
    }

    private static AssistantMessage text(String s) {
        return FakeProviderImpl.reply(List.of(new ContentBlock.Text(s)), StopReasonEnum.STOP);
    }

    @Test
    void the_tasks_entry_sits_between_the_call_and_its_result_and_survives_compaction_and_resume() throws IOException {
        SessionStore store = new JsonlSessionStoreImpl(home.resolve("sessions"));
        SessionId id = store.create(cwd);
        FakeProviderImpl provider = FakeProviderImpl.scripted(List.of(todoCall(), text("listed"),
            text("## Task\nplan\n## Done\nlisted\n## Files touched\n- none\n## Decisions\n- none\n"
                + "## Open issues\n- none\n## Next step\nDo A")));
        Repl repl = repl(store, id, provider, "plan the work\n");
        repl.run();

        List<SessionEntry> entries = store.readAll(id);
        SessionEntry.MessageEntry call = entries.stream().filter(SessionEntry.MessageEntry.class::isInstance)
            .map(SessionEntry.MessageEntry.class::cast)
            .filter(e -> e.message() instanceof AssistantMessage a && !a.toolCalls().isEmpty()).findFirst()
            .orElseThrow();
        SessionEntry.CustomEntry tasks = entries.stream().filter(SessionEntry.CustomEntry.class::isInstance)
            .map(SessionEntry.CustomEntry.class::cast).findFirst().orElseThrow();
        SessionEntry.MessageEntry result = entries.stream().filter(SessionEntry.MessageEntry.class::isInstance)
            .map(SessionEntry.MessageEntry.class::cast).filter(e -> e.message() instanceof ToolResultMessage)
            .findFirst().orElseThrow();
        assertThat(tasks.customType()).isEqualTo("tasks");
        assertThat(tasks.parentId()).contains(call.id());
        assertThat(result.parentId()).contains(tasks.id());
        List<Message> context = store.buildContext(id, store.leafId(id).orElseThrow());
        assertThat(context).hasSize(4);

        Repl.Compaction c = repl.compactNow();
        assertThat(c.compacted()).as("the projection of the session equals the loop's messages").isTrue();

        out.reset();
        Repl resumed = repl(new JsonlSessionStoreImpl(home.resolve("sessions")), id, provider, "");
        resumed.command("/todo");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Task list (0/1 completed)", "[>] 1. Do A");
    }

    @Test
    void interrupting_a_command_also_cancels_its_request() throws IOException {
        SessionStore store = new JsonlSessionStoreImpl(home.resolve("sessions"));
        SessionId id = store.create(cwd);
        AtomicReference<Repl> ref = new AtomicReference<>();
        List<Boolean> cancelledDuringSummary = new ArrayList<>();
        FakeProviderImpl script = FakeProviderImpl.scripted(List.of(text("one"), text("two")));
        Provider provider = new Provider() {
            private int calls;

            @Override
            public String id() {
                return FakeProviderImpl.FAKE_ID;
            }

            @Override
            public Set<ModelRef.CapabilityEnum> capabilities(ModelRef model) {
                return Set.of(ModelRef.CapabilityEnum.TOOLS);
            }

            @Override
            public AssistantMessage stream(ChatRequest request, Consumer<StreamEvent> sink, CancelToken cancel) {
                if (++calls <= 2) {
                    return script.stream(request, sink, cancel);
                }
                assertThat(ref.get().interrupt()).isTrue();
                cancelledDuringSummary.add(cancel.isCancelled());
                cancelledDuringSummary.add(Thread.currentThread().isInterrupted());
                return script.stream(request, sink, CancelToken.none());
            }
        };
        Repl repl = repl(store, id, provider, "first\nsecond\n");
        ref.set(repl);
        Commands.builtins(repl).forEach(repl.setup().host()::registerCommand);
        repl.run();
        repl.command("/compact");
        assertThat(cancelledDuringSummary).containsExactly(true, true);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(repl.interrupt()).isFalse();
    }
}
