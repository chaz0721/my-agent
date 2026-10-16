/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;
import dev.pai.model.ToolResultMessage;

import static dev.pai.ext.subagent.Harness.say;
import static dev.pai.ext.subagent.Harness.task;
import static dev.pai.ext.subagent.Harness.text;
import static dev.pai.ext.subagent.Harness.use;
import static org.assertj.core.api.Assertions.assertThat;

class SubagentExtensionTest {

    private static final String SECRET_BODY = "the needle lives here, line 42";

    @TempDir
    Path tmp;

    private static long results(ChatRequest r) {
        return r.messages().stream().filter(ToolResultMessage.class::isInstance).count();
    }

    private static List<String> resultTexts(ChatRequest r) {
        return r.messages().stream().filter(ToolResultMessage.class::isInstance)
            .map(m -> ((ContentBlock.Text)((ToolResultMessage)m).content().get(0)).text()).toList();
    }

    @Test
    void explore_sees_only_read_tools_without_project_context_and_non_read_only_calls_are_blocked() throws Exception {
        List<AssistantMessage> script = List.of(use("read", "{\"path\":\"a.txt\"}"),
            use("bash", "{\"command\":\"ls\"}"), use("bash", "{\"command\":\"rm a.txt\"}"),
            use("write", "{\"path\":\"a.txt\",\"content\":\"x\"}"), say("found it in a.txt:42"));
        Harness h = new Harness(tmp, Map.of(), r -> script.get((int)results(r)));
        Files.writeString(h.cwd.resolve("a.txt"), SECRET_BODY);
        Files.writeString(h.cwd.resolve("PAI.md"), "project rule: never mind");

        ToolResult r =
            h.call(SubagentTools.SPAWN, "{\"task\":\"find the needle\",\"agentType\":\"explore\"}", CancelToken.none());

        assertThat(r.isError()).isFalse();
        assertThat(text(r)).contains("found it in a.txt:42").contains("completed");
        ChatRequest first = h.requests.get(0);
        assertThat(first.tools()).extracting(ToolDefinition::name).containsExactly("read", "bash");
        assertThat(first.systemPrompt().orElseThrow()).doesNotContain("project rule").contains("read-only exploration");
        assertThat(first.messages()).hasSize(1);
        List<String> seen = resultTexts(h.requests.get(4));
        assertThat(seen.get(0)).isEqualTo(SECRET_BODY);
        assertThat(seen.get(1)).isEqualTo("ran ls");
        assertThat(seen.get(2)).startsWith("tool call blocked: sub-agent type explore is read-only");
        assertThat(seen.get(3)).startsWith("unknown tool: write");
        assertThat(Files.readString(h.cwd.resolve("a.txt"))).isEqualTo(SECRET_BODY);
        assertThat(h.progress).hasSize(5).allMatch(l -> l.matches("\\[sa-[0-9a-f]{8} explore\\] turn \\d: .*"));
        assertThat(h.progress.get(0)).endsWith("turn 1: read a.txt");
        assertThat(h.progress.get(2)).endsWith("turn 3: bash rm a.txt");
        assertThat(h.progress.get(4)).endsWith("turn 5: replied");
    }

    @Test
    void general_gets_the_project_context_and_no_tools_of_its_own_so_it_cannot_nest() throws Exception {
        Harness h = new Harness(tmp, Map.of(), r -> say("done"));
        Files.writeString(h.cwd.resolve("PAI.md"), "project rule: tabs");
        h.call(SubagentTools.SPAWN, "{\"task\":\"do it\"}", CancelToken.none());
        ChatRequest first = h.requests.get(0);
        assertThat(first.systemPrompt().orElseThrow()).contains("project rule: tabs");
        assertThat(first.tools()).extracting(ToolDefinition::name).containsExactly("read", "write", "bash")
            .doesNotContain(SubagentTools.SPAWN, SubagentTools.RESULT, SubagentTools.CANCEL);
    }

    @Test
    void the_parent_gets_one_entry_without_what_the_sub_agent_read_and_the_side_chain_points_back() throws Exception {
        List<AssistantMessage> script = List.of(use("read", "{\"path\":\"a.txt\"}"), say("it is on line 42"));
        Harness h = new Harness(tmp, Map.of(), r -> script.get((int)results(r)));
        Files.writeString(h.cwd.resolve("a.txt"), SECRET_BODY);

        h.call(SubagentTools.SPAWN, "{\"task\":\"where is the needle\",\"agentType\":\"explore\"}", CancelToken.none());

        assertThat(h.entries).hasSize(1);
        SessionEntry.CustomEntry e = h.entries.get(0);
        assertThat(e.customType()).isEqualTo("subagent");
        JsonNode d = e.data();
        assertThat(d.path("runId").asText()).startsWith("sa-");
        assertThat(d.path("type").asText()).isEqualTo("explore");
        assertThat(d.path("task").asText()).isEqualTo("where is the needle");
        assertThat(d.path("resultSummary").asText()).isEqualTo("it is on line 42");
        assertThat(d.path("usage").has("input")).isTrue();
        assertThat(d.toString()).doesNotContain("needle lives");

        Path side = h.home.resolve("sessions").resolve(d.path("sideChain").asText());
        assertThat(side).exists().hasFileName(d.path("runId").asText() + ".jsonl");
        assertThat(side.getParent().getFileName().toString()).isEqualTo(Harness.PARENT_SESSION);
        assertThat(side.getParent().getParent().getParent().getFileName().toString()).isEqualTo("0123456789abcdef");
        String header = Files.readAllLines(side, StandardCharsets.UTF_8).get(0);
        assertThat(Json.mapper().readTree(header).path("parentSession").asText()).isEqualTo(Harness.PARENT_SESSION);
        assertThat(Files.readString(side)).contains("needle lives");

        String sid = d.path("runId").asText().substring(SideChain.RUN_PREFIX.length());
        JsonlSessionStoreImpl store = new JsonlSessionStoreImpl(side.getParent());
        assertThat(store.header(new SessionId(sid)).id().value()).isEqualTo(sid);
        List<SessionEntry> back = store.readAll(new SessionId(sid));
        assertThat(back).hasSize(4).allMatch(SessionEntry.MessageEntry.class::isInstance);
        assertThat(store.buildContext(new SessionId(sid), back.get(3).id())).hasSize(4);
    }

    @Test
    void at_most_max_concurrency_run_at_once_and_a_failure_does_not_touch_its_siblings() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Harness h = new Harness(tmp, Map.of("subagent.max-concurrency", "2"), r -> {
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return task(r).startsWith("fail") ? FakeProviderImpl.reply(List.of(), StopReasonEnum.ERROR)
                : say("ok " + task(r));
        });
        List<String> ids = new java.util.ArrayList<>();
        for (String t : List.of("one", "fail two", "three", "four")) {
            String out =
                text(h.call(SubagentTools.SPAWN, "{\"task\":\"" + t + "\",\"background\":true}", CancelToken.none()));
            ids.add(out.split(" ")[1]);
        }
        List<ToolResult> got = new java.util.ArrayList<>();
        for (String id : ids) {
            got.add(h.call(SubagentTools.RESULT, "{\"runId\":\"" + id + "\"}", CancelToken.none()));
        }
        assertThat(peak.get()).isLessThanOrEqualTo(2);
        assertThat(got.get(1).isError()).isTrue();
        assertThat(text(got.get(1))).contains("provider_error");
        assertThat(List.of(got.get(0), got.get(2), got.get(3))).allSatisfy(r -> assertThat(r.isError()).isFalse());
        assertThat(text(got.get(3))).contains("ok four");
        h.parentRequest(CancelToken.none());
        assertThat(h.entries).hasSize(4);
    }

    @Test
    void cancelling_the_parent_cancels_every_run_and_their_threads_end() throws Exception {
        CountDownLatch started = new CountDownLatch(3);
        Harness h = new Harness(tmp, Map.of(), r -> {
            started.countDown();
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return use("bash", "{\"command\":\"ls " + results(r) + "\"}");
        });
        java.util.concurrent.atomic.AtomicReference<String> fgResult =
            new java.util.concurrent.atomic.AtomicReference<>();
        CancelTokenSource parent = new CancelTokenSource();
        h.parentRequest(parent);
        for (int i = 0; i < 2; i++) {
            h.call(SubagentTools.SPAWN, "{\"task\":\"loop " + i + "\",\"background\":true}", CancelToken.none());
        }
        Thread fg = Thread.ofPlatform().start(() -> {
            try {
                fgResult.set(text(h.call(SubagentTools.SPAWN, "{\"task\":\"fg\"}", parent)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        parent.cancel();
        fg.join(10_000L);
        assertThat(fg.isAlive()).isFalse();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (subagentThreads() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        assertThat(subagentThreads()).isZero();
        assertThat(fgResult.get()).contains("aborted");
        h.parentRequest(CancelToken.none());
        assertThat(h.entries).hasSize(3).allMatch(e -> "ABORTED".equals(e.data().path("reason").asText()));
    }

    private static long subagentThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("pai-subagent-"))
            .filter(Thread::isAlive).count();
    }

    /** Writes {@code content} to {@code file} (slowly) in its first turn, then says done. */
    private static AssistantMessage writer(ChatRequest r) {
        if (results(r) > 0) {
            return say("done");
        }
        String[] t = task(r).split(" ");
        return use("write", "{\"path\":\"" + t[1] + "\",\"content\":\"" + t[0] + "\",\"millis\":300}");
    }

    private static Thread spawnInParallel(Harness h, String task, List<ToolResult> out) {
        return Thread.ofPlatform().start(() -> {
            try {
                out.add(h.call(SubagentTools.SPAWN, "{\"task\":\"" + task + "\"}", CancelToken.none()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void two_foreground_runs_writing_the_same_real_file_take_turns_and_both_writes_stay() throws Exception {
        Harness h = new Harness(tmp, Map.of(), SubagentExtensionTest::writer);
        Files.writeString(h.cwd.resolve("shared.txt"), "");
        String other = link(h.cwd.resolve("link.txt"), h.cwd.resolve("shared.txt")) ? "link.txt" : "shared.txt";
        List<ToolResult> got = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread a = spawnInParallel(h, "A shared.txt", got);
        Thread b = spawnInParallel(h, "B " + other, got);
        a.join(20_000L);
        b.join(20_000L);
        assertThat(got).hasSize(2).allSatisfy(r -> assertThat(r.isError()).isFalse());
        assertThat(h.writes).hasSize(4);
        assertThat(h.writes.get(0)).startsWith("start:");
        assertThat(h.writes.get(1)).startsWith("end:");
        assertThat(Files.readString(h.cwd.resolve("shared.txt"))).hasSize(2).contains("A").contains("B");
        assertThat(h.notices).anyMatch(n -> n.matches(
            "\\[sub-agent sa-[0-9a-f]{8}\\] waits for \\[sub-agent sa-[0-9a-f]{8}\\] " + "writing .*shared\\.txt"));
        assertThat(h.notices).anyMatch(n -> n.matches("\\[sub-agent sa-[0-9a-f]{8}\\] wants to run write .*"));
    }

    @Test
    void the_parent_takes_no_lock_so_its_own_foreground_sub_agent_never_waits_for_it() throws Exception {
        Harness h = new Harness(tmp, Map.of(), SubagentExtensionTest::writer);
        Tool write = h.host.tools().get("write").orElseThrow();
        ContentBlock.ToolCall parentEdit =
            new ContentBlock.ToolCall("p1", "write", (com.fasterxml.jackson.databind.node.ObjectNode)Json.mapper()
                .readTree("{\"path\":\"a.txt\",\"content\":\"P\"}"));
        assertThat(h.host.hooks().beforeToolCall(parentEdit, write, Harness.state(CancelToken.none())).allowed())
            .isTrue();
        long start = System.nanoTime();
        ToolResult r = h.call(SubagentTools.SPAWN, "{\"task\":\"C a.txt\"}", CancelToken.none());
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(10L);
        assertThat(r.isError()).isFalse();
        assertThat(h.notices).noneMatch(n -> n.contains("waits for"));
        h.host.hooks().afterToolCall(parentEdit, ToolResult.text("ok"), Harness.state(CancelToken.none()));
    }

    @Test
    void a_background_run_cannot_make_calls_that_are_not_read_only() throws Exception {
        Harness h = new Harness(tmp, Map.of(), r -> results(r) == 0 ? use("write", "{\"path\":\"x.txt\"}")
            : results(r) == 1 ? use("bash", "{\"command\":\"ls\"}") : say("done"));
        String started = text(h.call(SubagentTools.SPAWN, "{\"task\":\"w\",\"background\":true}", CancelToken.none()));
        h.call(SubagentTools.RESULT, "{\"runId\":\"" + started.split(" ")[1] + "\"}", CancelToken.none());
        List<String> seen = resultTexts(h.requests.get(2));
        assertThat(seen.get(0)).isEqualTo("tool call blocked: " + SubagentRuns.BACKGROUND_BLOCK);
        assertThat(seen.get(1)).isEqualTo("ran ls");
        assertThat(h.writes).isEmpty();
    }

    @Test
    void spawning_a_read_only_type_is_a_read_only_call() throws Exception {
        Harness h = new Harness(tmp, Map.of(), r -> say("ok"));
        Tool spawn = h.host.tools().get(SubagentTools.SPAWN).orElseThrow();
        assertThat(Tool.effectOf(spawn, Json.mapper().readTree("{\"task\":\"t\",\"agentType\":\"explore\"}")))
            .isEqualTo(ToolEffectEnum.READ_ONLY);
        assertThat(Tool.effectOf(spawn, Json.mapper().readTree("{\"task\":\"t\",\"agentType\":\"reviewer\"}")))
            .isEqualTo(ToolEffectEnum.READ_ONLY);
        assertThat(Tool.effectOf(spawn, Json.mapper().readTree("{\"task\":\"t\"}"))).isEqualTo(ToolEffectEnum.EXECUTE);
        assertThat(Tool.effectOf(spawn, Json.mapper().readTree("{\"task\":\"t\",\"agentType\":\"nope\"}")))
            .isEqualTo(ToolEffectEnum.EXECUTE);
    }

    @Test
    void the_parent_approval_hooks_check_the_paths_of_a_sub_agent_read_command() throws Exception {
        Path outside =
            Files.writeString(Files.createDirectories(tmp.resolve("elsewhere")).resolve("secret.txt"), "TOPSECRET-Q9");
        List<AssistantMessage> script = List.of(use("bash", "{\"command\":\"cat inside.txt\"}"),
            use("bash", "{\"command\":\"cat out.txt\"}"), say("done"));
        Harness h =
            new Harness(tmp, Map.of(), r -> script.get((int)results(r)), List.of(new dev.pai.ext.hitl.HitlExtension()));
        Files.writeString(h.cwd.resolve("inside.txt"), "I");
        org.junit.jupiter.api.Assumptions.assumeTrue(link(h.cwd.resolve("out.txt"), outside), "no symbolic links");
        h.call(SubagentTools.SPAWN, "{\"task\":\"look\",\"agentType\":\"explore\"}", CancelToken.none());
        List<String> seen = resultTexts(h.requests.get(2));
        assertThat(seen.get(0)).isEqualTo("I");
        assertThat(seen.get(1)).startsWith("tool call blocked").doesNotContain("TOPSECRET-Q9");
    }

    private static boolean link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }

    @Test
    void user_agent_files_add_types_and_unknown_types_are_refused() throws Exception {
        Path agents = Files.createDirectories(tmp.resolve("home").resolve("agents"));
        Files.writeString(agents.resolve("docs.md"), "---\nname: docs\ndescription: writes docs\ntools: [read]\n"
            + "project-context: false\n---\nYou write docs.\n");
        Harness h = new Harness(tmp, Map.of(), r -> say("ok"));
        h.call(SubagentTools.SPAWN, "{\"task\":\"t\",\"agentType\":\"docs\"}", CancelToken.none());
        assertThat(h.requests.get(0).tools()).extracting(ToolDefinition::name).containsExactly("read");
        assertThat(h.requests.get(0).systemPrompt().orElseThrow()).contains("You write docs.");
        ToolResult bad = h.call(SubagentTools.SPAWN, "{\"task\":\"t\",\"agentType\":\"nope\"}", CancelToken.none());
        assertThat(bad.isError()).isTrue();
        assertThat(text(bad)).contains("unknown agent type: nope").contains("docs");
        assertThat(h.host.tools().get(SubagentTools.SPAWN).orElseThrow().definition().description())
            .contains("- docs: writes docs").contains("- explore:");
    }

    @Test
    void empty_tool_lists_unknown_tool_names_and_bad_limits_are_reported() throws Exception {
        Path agents = Files.createDirectories(tmp.resolve("home").resolve("agents"));
        Files.writeString(agents.resolve("all.md"), "---\ntools: []\n---\nAll.\n");
        Files.writeString(agents.resolve("odd.md"), "---\ntools: read, frobnicate\n---\nOdd.\n");
        Harness h = new Harness(tmp, Map.of("subagent.max-concurrency", "0"), r -> say("ok"));
        h.call(SubagentTools.SPAWN, "{\"task\":\"t\",\"agentType\":\"all\"}", CancelToken.none());
        h.call(SubagentTools.SPAWN, "{\"task\":\"t\",\"agentType\":\"odd\"}", CancelToken.none());
        h.call(SubagentTools.SPAWN, "{\"task\":\"t\",\"agentType\":\"odd\"}", CancelToken.none());
        assertThat(h.requests.get(0).tools()).extracting(ToolDefinition::name).containsExactly("read", "write", "bash");
        assertThat(h.notices).contains("subagent: agent type all lists no tools, so it gets all tools")
            .contains("subagent: ignoring subagent.max-concurrency=0 (not a positive number); using 4")
            .containsOnlyOnce("subagent: unknown tool frobnicate in agent type odd");
    }

    @Test
    void parent_prompts_offering_spawn_agent_get_fixed_guidance_and_sub_agents_do_not() throws Exception {
        Harness h = new Harness(tmp, Map.of(), r -> say("done"));
        ToolDefinition spawn = h.host.tools().get(SubagentTools.SPAWN).orElseThrow().definition();
        ChatRequest offered = new ChatRequest(Harness.MODEL, Optional.of("parent system"), List.of(), List.of(spawn),
            dev.pai.model.ChatOptions.defaults());
        String first =
            h.host.hooks().prepareRequest(offered, Harness.state(CancelToken.none())).systemPrompt().orElseThrow();
        assertThat(first).startsWith("parent system\n\n" + SubagentRuns.GUIDANCE).contains("agentType explore");
        assertThat(SubagentRuns.GUIDANCE).startsWith("<sub-agents>\nIf you have spawn_agent:")
            .hasSizeLessThanOrEqualTo(250);
        assertThat(h.host.hooks().prepareRequest(offered, Harness.state(CancelToken.none())).systemPrompt())
            .contains(first);
        ChatRequest without = new ChatRequest(Harness.MODEL, Optional.of("parent system"), List.of(), List.of(),
            dev.pai.model.ChatOptions.defaults());
        assertThat(h.host.hooks().prepareRequest(without, Harness.state(CancelToken.none())).systemPrompt())
            .contains("parent system");
        h.call(SubagentTools.SPAWN, "{\"task\":\"look\",\"agentType\":\"explore\"}", CancelToken.none());
        assertThat(h.requests.get(0).systemPrompt().orElseThrow()).doesNotContain("<sub-agents>");
    }

    @Test
    void the_progress_line_keeps_the_format_the_tui_parses() {
        assertThat(SubagentExtension.progressLine("sa-1a2b3c4d", "explore", 3, "replied"))
            .isEqualTo("[sa-1a2b3c4d explore] turn 3: replied");
    }
}
