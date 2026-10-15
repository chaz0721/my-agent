/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
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

    @Test
    void two_background_runs_writing_the_same_real_file_take_turns_and_both_writes_stay() throws Exception {
        Harness h = new Harness(tmp, Map.of(), r -> {
            if (results(r) > 0) {
                return say("done");
            }
            String file = "B".equals(task(r)) ? "link.txt" : "shared.txt";
            return use("write", "{\"path\":\"" + file + "\",\"content\":\"" + task(r).charAt(0) + "\",\"millis\":300}");
        });
        Files.writeString(h.cwd.resolve("shared.txt"), "");
        boolean linked = link(h.cwd.resolve("link.txt"), h.cwd.resolve("shared.txt"));
        String a = text(h.call(SubagentTools.SPAWN, "{\"task\":\"A\",\"background\":true}", CancelToken.none()));
        String b = text(h.call(SubagentTools.SPAWN, "{\"task\":\"B" + (linked ? "" : "x") + "\",\"background\":true}",
            CancelToken.none()));
        h.call(SubagentTools.RESULT, "{\"runId\":\"" + a.split(" ")[1] + "\"}", CancelToken.none());
        h.call(SubagentTools.RESULT, "{\"runId\":\"" + b.split(" ")[1] + "\"}", CancelToken.none());
        assertThat(h.writes).hasSize(4);
        assertThat(h.writes.get(0)).startsWith("start:");
        assertThat(h.writes.get(1)).startsWith("end:");
        assertThat(Files.readString(h.cwd.resolve("shared.txt"))).hasSize(2).contains("A").contains("B");
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
}
