/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import dev.pai.core.compaction.CharsTokenEstimatorImpl;
import dev.pai.core.compaction.CompactingHooks;
import dev.pai.core.compaction.CompactionPolicy;
import dev.pai.core.compaction.MicroCompactionPolicy;
import dev.pai.core.compaction.MicroCompactor;
import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.internal.LoopSnapshot;
import dev.pai.core.loop.AgentConfig;
import dev.pai.core.loop.AgentLoop;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionTree;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Performance baseline (M7 7D), run by {@code build/bench.ps1} / {@code build/bench.sh}: skipped unless
 * {@code -Dpai.bench=1}. Prints {@code BENCH|name|value|unit|how} lines; {@code -Dpai.bench.home=
 * 
<dir>
 * } also leaves a 1000-entry session there for the script's end-to-end {@code --resume} timing.
 */
class BenchTest {

    private static final ModelRef MODEL = new ModelRef(FakeProviderImpl.FAKE_ID, "fake",
        java.net.URI.create("http://fake.invalid"), 128_000, 8_000, java.util.Set.of(ModelRef.CapabilityEnum.TOOLS));
    private static final int ENTRIES = 1_000;

    private final List<String> out = new ArrayList<>();

    private void report(String name, double value, String unit, String how) {
        String line = String.format(Locale.ROOT, "BENCH|%s|%.3f|%s|%s", name, value, unit, how);
        out.add(line);
        System.out.println(line);
    }

    /** Median in milliseconds of {@code runs} timed calls after {@code warmup} untimed ones. */
    private static double median(int warmup, int runs, LongSupplier body) {
        for (int i = 0; i < warmup; i++) {
            body.getAsLong();
        }
        long[] t = new long[runs];
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            body.getAsLong();
            t[i] = System.nanoTime() - start;
        }
        Arrays.sort(t);
        return t[runs / 2] / 1e6;
    }

    private static String text(int chars, int seed) {
        StringBuilder sb = new StringBuilder(chars);
        while (sb.length() < chars) {
            sb.append("line ").append(seed).append(' ').append(sb.length()).append(" of some tool output\n");
        }
        return sb.substring(0, chars);
    }

    private static AssistantMessage call(String id, String path) {
        return FakeProviderImpl.reply(
            List.of(new ContentBlock.ToolCall(id, "read", Json.mapper().createObjectNode().put("path", path))),
            StopReasonEnum.TOOL_USE);
    }

    private static ToolResultMessage result(String id, int chars) {
        return new ToolResultMessage(id, "read", List.of(new ContentBlock.Text(text(chars, id.hashCode()))), false,
            Instant.EPOCH);
    }

    @Test
    void baseline() throws IOException {
        assumeTrue("1".equals(System.getProperty("pai.bench")), "run with -Dpai.bench=1");
        Path home = Path.of(System.getProperty("pai.bench.home", System.getProperty("java.io.tmpdir")))
            .resolve("pai-bench-" + System.nanoTime());
        resume(home);
        readLoop(home);
        micro();
        summary(home);
        sidebar(home);
        Path file = Path.of(System.getProperty("pai.bench.out", home.resolve("bench.txt").toString()));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, out, StandardCharsets.UTF_8);
        assertThat(out).hasSize(9);
    }

    /** (2) What {@code --resume} does with the store: leaf, then the context of the leaf. */
    private void resume(Path home) throws IOException {
        Path cwd = home.resolve("proj");
        Files.createDirectories(cwd);
        JsonlSessionStoreImpl store = new JsonlSessionStoreImpl(home.resolve("home").resolve(Main.SESSIONS_DIR));
        SessionId id = store.create(cwd);
        Optional<String> leaf = Optional.empty();
        for (int i = 0; i < ENTRIES; i++) {
            Message m = i % 2 == 0 ? UserMessage.text("question " + i + " " + text(200, i)) : FakeProviderImpl
                .reply(List.of(new ContentBlock.Text("answer " + i + " " + text(400, i))), StopReasonEnum.STOP);
            leaf = Optional.of(store.append(id, new SessionEntry.MessageEntry("", leaf, Instant.now(), m)));
        }
        Path root = home.resolve("home").resolve(Main.SESSIONS_DIR);
        double ms = median(5, 21, () -> {
            JsonlSessionStoreImpl fresh = new JsonlSessionStoreImpl(root);
            return fresh.buildContext(id, fresh.leafId(id).orElseThrow()).size();
        });
        report("resume-1000-entries-store", ms, "ms", "new store, leafId + buildContext of a 1000-entry session");
    }

    /** (3) One run with one {@code read} call and a final reply; the provider is scripted, so no model time. */
    private void readLoop(Path home) throws IOException {
        Path cwd = home.resolve("proj");
        Files.writeString(cwd.resolve("README.md"), text(2_000, 1));
        ExtensionLoader loader = new ExtensionLoader();
        ExtensionHost host = loader.install(loader.loadFromServiceLoader(), ExtensionHost.Settings.of(home, cwd));
        AgentConfig cfg = AgentConfig.of(MODEL, "system");
        double ms = median(200, 501, () -> {
            FakeProviderImpl p = FakeProviderImpl.scripted(List.of(call("c1", "README.md"),
                FakeProviderImpl.reply(List.of(new ContentBlock.Text("done")), StopReasonEnum.STOP)));
            return new AgentLoop(p, host.tools(), host.hooks(), cfg, cwd)
                .run(List.of(), UserMessage.text("read it"), e -> {
                }, CancelToken.none()).size();
        });
        report("loop-one-read-call", ms, "ms", "AgentLoop.run: scripted read call on a 2 KB file + final reply, "
            + "classpath extensions (hitl auto-read, no ui) installed");
        host.unloadAll();
    }

    /** (4) Micro compaction of 200 messages: 50 turns of call + 3 KB result + call + small result. */
    private void micro() {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            msgs.add(call("a" + i, "f" + i));
            msgs.add(result("a" + i, 3_000));
            msgs.add(call("b" + i, "g" + i));
            msgs.add(result("b" + i, 100));
        }
        MicroCompactor micro = new MicroCompactor();
        MicroCompactionPolicy policy = MicroCompactionPolicy.defaults();
        double ms = median(500, 2_001, () -> micro.apply(msgs, policy).size());
        report("micro-compact-200-messages", ms, "ms", "MicroCompactor.apply, default policy (46 results trimmed)");
        CharsTokenEstimatorImpl est = new CharsTokenEstimatorImpl();
        report("micro-compact-200-tokens-before", est.estimate(msgs, List.of(), ""), "tokens", "chars/4 estimate");
        report("micro-compact-200-tokens-after", est.estimate(micro.apply(msgs, policy), List.of(), ""), "tokens",
            "chars/4 estimate of the projection");
    }

    /** (5) Token estimate before and after one full summary. */
    private void summary(Path home) {
        JsonlSessionStoreImpl store = new JsonlSessionStoreImpl(home.resolve("summary"));
        SessionId id = store.create(home);
        List<Message> context = new ArrayList<>();
        Optional<String> leaf = Optional.empty();
        for (int i = 0; i < 40; i++) {
            for (Message m : List.of(UserMessage.text("step " + i), call("s" + i, "src/F" + i + ".java"),
                result("s" + i, 4_000),
                FakeProviderImpl.reply(List.of(new ContentBlock.Text("ok " + i)), StopReasonEnum.STOP))) {
                leaf = Optional.of(store.append(id, new SessionEntry.MessageEntry("", leaf, Instant.now(), m)));
                context.add(m);
            }
        }
        String text = String.join("\n", "## Task", "Refactor F0..F39.", "## Done", "Read all forty files.",
            "## Files touched", "- src/F38.java: read", "- src/F39.java: read", "## Decisions", "- none",
            "## Open issues", "- none", "## Next step", "Edit F39.");
        CompactingHooks hooks = new CompactingHooks(new CharsTokenEstimatorImpl(), CompactionPolicy.defaults(),
            (m, s, c) -> text, store, new CompactingHooks.Target(() -> id));
        CharsTokenEstimatorImpl est = new CharsTokenEstimatorImpl();
        LoopSnapshot state = new LoopSnapshot(context, MODEL, Usage.ZERO, 0, home, false, CancelToken.none(), leaf);
        List<Message> after = hooks.compact(List.copyOf(context), state, true);
        report("summary-tokens-before", est.estimate(context, List.of(), ""), "tokens",
            "160 messages (40 turns, 4 KB read results), chars/4");
        report("summary-tokens-after", est.estimate(after, List.of(), ""), "tokens",
            "after a forced compaction with a fixed 6-section summary (" + after.size() + " messages kept)");
    }

    /** Sidebar lookups after each append on a 1000-entry session: full re-read (M6) vs incremental parsing (M7). */
    private void sidebar(Path home) {
        JsonlSessionStoreImpl store = new JsonlSessionStoreImpl(home.resolve("sidebar"));
        SessionId id = store.create(home);
        AtomicReference<Optional<String>> leaf = new AtomicReference<>(Optional.empty());
        for (int i = 0; i < ENTRIES; i++) {
            leaf.set(Optional.of(store.append(id,
                new SessionEntry.MessageEntry("", leaf.get(), Instant.now(), UserMessage.text(text(300, i))))));
        }
        SessionEntriesImpl entries = new SessionEntriesImpl(store,
            () -> Optional.of(new SessionEntriesImpl.Cursor(() -> id, leaf::get, x -> leaf.set(Optional.of(x)))),
            Clock.systemUTC(), SessionEntriesImpl.filesUnder(home.resolve("sidebar")));
        entries.latest("tasks");
        double incremental = median(20, 101, () -> {
            leaf.set(Optional.of(
                store.append(id, new SessionEntry.MessageEntry("", leaf.get(), Instant.now(), UserMessage.text("x")))));
            return entries.latest("tasks").isPresent() ? 1L : 0L;
        });
        double full = median(20, 101, () -> {
            leaf.set(Optional.of(
                store.append(id, new SessionEntry.MessageEntry("", leaf.get(), Instant.now(), UserMessage.text("x")))));
            return SessionTree.of(store.readAll(id)).path(leaf.get().orElseThrow()).size();
        });
        report("sidebar-lookup-full-reread", full, "ms", "append + SessionTree.of(readAll).path, ~1000 entries");
        report("sidebar-lookup-incremental", incremental, "ms", "append + SessionEntriesImpl.latest, ~1000 entries");
    }
}
