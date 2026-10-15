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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
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

    private Repl repl(SessionStore store, SessionId id, FakeProviderImpl provider, String input) {
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
        FakeProviderImpl provider = FakeProviderImpl.scripted(List.of(todoCall(), text("listed"), text("summary")));
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
}
