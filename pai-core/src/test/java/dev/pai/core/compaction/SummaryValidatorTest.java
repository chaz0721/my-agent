/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.internal.LoopSnapshot;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionTree;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolRegistry;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

import static dev.pai.core.compaction.CompactionPolicyTest.model;
import static dev.pai.core.compaction.CompactionPolicyTest.say;
import static dev.pai.core.compaction.CompactionPolicyTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryValidatorTest {

    static final String VALID = String.join("\n", "## Task", "Rename the greeting.", "## Done", "Read the sources.",
        "## Files touched", "- src/Main.java: prints lighthouse instead of harbor", "## Decisions",
        "- keep one class: the project is tiny", "## Open issues", "- none", "## Next step", "Run the tests.");

    /** The test conversations write files with {@code edit} only. */
    static final Predicate<ContentBlock.ToolCall> WRITES = c -> "edit".equals(c.name());

    private static final Instant T0 = Instant.parse("2026-10-15T08:00:00Z");

    @TempDir
    Path root;

    static AssistantMessage edit(String id, String path) {
        return FakeProviderImpl.reply(
            List.of(new ContentBlock.ToolCall(id, "edit",
                Json.mapper().createObjectNode().put("path", path).put("oldText", "a").put("newText", "b"))),
            StopReasonEnum.TOOL_USE);
    }

    static ToolResultMessage edited(String id) {
        return new ToolResultMessage(id, "edit", List.of(new ContentBlock.Text("edited")), false, Instant.EPOCH);
    }

    private static List<Message> editing(String path) {
        return List.of(user("change it"), edit("e1", path), edited("e1"), say("done"));
    }

    private static String without(String section) {
        StringBuilder sb = new StringBuilder();
        boolean skip = false;
        for (String l : VALID.lines().toList()) {
            if (l.startsWith("## ")) {
                skip = l.equals(section);
            }
            if (!skip) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void a_complete_summary_passes() {
        assertThat(SummaryValidator.problems(VALID, editing("src/Main.java"), WRITES, 10_000)).isEmpty();
    }

    @Test
    void each_missing_section_is_reported() {
        for (String s : SummaryValidator.SECTIONS) {
            assertThat(SummaryValidator.problems(without(s), List.of(), WRITES, 10_000))
                .containsExactly("missing section " + s);
        }
    }

    @Test
    void empty_files_touched_fails_only_when_files_were_written() {
        String noFiles = VALID.replace("- src/Main.java: prints lighthouse instead of harbor", "- none");
        assertThat(SummaryValidator.problems(noFiles, List.of(user("hi"), say("hello")), WRITES, 10_000)).isEmpty();
        assertThat(SummaryValidator.problems(noFiles, editing("a/B.java"), WRITES, 10_000)).singleElement().asString()
            .contains("Files touched is empty", "a/B.java");
    }

    @Test
    void a_summary_over_budget_fails() {
        assertThat(SummaryValidator.problems(VALID, List.of(), WRITES, 50)).singleElement().asString()
            .contains("more than 50");
    }

    @Test
    void normalizing_adds_left_out_written_files_and_drops_duplicates() {
        String dup = VALID.replace("- src/Main.java: prints lighthouse instead of harbor",
            "- `src/Main.java`: first\n- src/Main.java: again\n- README.md: read");
        List<Message> msgs = new ArrayList<>(editing("src/Main.java"));
        msgs.addAll(List.of(edit("e2", "docs/notes.md"), edited("e2")));
        String out = SummaryValidator.normalize(dup, msgs, WRITES);
        assertThat(out).contains("- `src/Main.java`: first\n- README.md: read\n- docs/notes.md: changed")
            .doesNotContain("again");
        String none = VALID.replace("- src/Main.java: prints lighthouse instead of harbor", "- none");
        assertThat(SummaryValidator.normalize(none, editing("x/Y.java"), WRITES))
            .contains("## Files touched\n- x/Y.java: changed (details not summarized)\n## Decisions");
    }

    @Test
    void loose_headings_are_made_canonical() {
        String loose = String.join("\n", "### task", "Rename.", "**Done**", "read", "## Files touched:",
            "- a.txt: read", "#### DECISIONS", "- none", "**Open issues:**", "- none", "  ## next step  ", "stop",
            "- Done is a word here");
        String canon = SummaryValidator.canonical(loose);
        assertThat(SummaryValidator.problems(canon, List.of(), WRITES, 10_000)).isEmpty();
        assertThat(canon.lines().filter(l -> l.startsWith("## "))).containsExactlyElementsOf(SummaryValidator.SECTIONS);
        assertThat(canon).contains("- Done is a word here");
    }

    @Test
    void the_file_list_keeps_earlier_files_and_matches_full_paths() {
        String earlier = VALID.replace("- src/Main.java: prints lighthouse instead of harbor",
            "- old/Setup.java: created the setup\n- src/Main.java: read");
        List<Message> msgs = new ArrayList<>();
        msgs.add(SessionTree.summaryMessage(earlier, Instant.EPOCH));
        msgs.addAll(List.of(edit("e1", "src/Main.java"), edited("e1"), edit("e2", "other/Main.java"), edited("e2")));
        String now = VALID.replace("- src/Main.java: prints lighthouse instead of harbor",
            "- D:\\repo\\src\\Main.java: prints lighthouse");
        String out = SummaryValidator.normalize(now, msgs, WRITES);
        assertThat(out).contains("- old/Setup.java: created the setup", "- other/Main.java: changed")
            .doesNotContain("- src/Main.java");
    }

    @Test
    void failed_writes_are_not_files_touched() {
        ToolResultMessage failed =
            new ToolResultMessage("e1", "edit", List.of(new ContentBlock.Text("no match")), true, Instant.EPOCH);
        List<Message> msgs = List.of(user("x"), edit("e1", "a/B.java"), failed, say("failed"));
        assertThat(SummaryValidator.writtenPaths(msgs, WRITES)).isEmpty();
        String noFiles = VALID.replace("- src/Main.java: prints lighthouse instead of harbor", "- none");
        assertThat(SummaryValidator.problems(noFiles, msgs, WRITES, 10_000)).isEmpty();
    }

    private JsonlSessionStoreImpl store;
    private SessionId session;
    private Optional<String> leaf = Optional.empty();
    private final List<Message> context = new ArrayList<>();

    private CompactingHooks hooks(List<String> replies, List<ChatRequest> requests) {
        store = new JsonlSessionStoreImpl(root.resolve("s"), false, Clock.fixed(T0, ZoneOffset.UTC));
        session = store.create(root);
        for (Message m : List.of(user("rename " + "x".repeat(400)), edit("e1", "src/Main.java"), edited("e1"),
            say("renamed"), user("next"), say("ok"), user("more"), say("fine"))) {
            leaf = Optional.of(store.append(session, new SessionEntry.MessageEntry("", leaf, T0, m)));
            context.add(m);
        }
        FakeProviderImpl provider = new FakeProviderImpl("fake", req -> {
            requests.add(req);
            return FakeProviderImpl.reply(List.of(new ContentBlock.Text(replies.get(requests.size() - 1))),
                StopReasonEnum.STOP);
        });
        return new CompactingHooks(new CharsTokenEstimatorImpl(), new CompactionPolicy(0, 0, 3),
            new ModelSummarizerImpl(() -> provider, registry(ToolEffectEnum.WRITE_FS)), store,
            new CompactingHooks.Target(() -> session));
    }

    /** A registry whose {@code edit} tool has the given effect. */
    static ToolRegistry registry(ToolEffectEnum editEffect) {
        ToolRegistry r = new ToolRegistry();
        r.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("edit", "edit", JsonSchema.object().build());
            }

            @Override
            public ToolEffectEnum effect() {
                return editEffect;
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolContext ctx) {
                return ToolResult.text("edited");
            }
        }, "test");
        return r;
    }

    @Test
    void writing_calls_are_told_by_the_effect_of_their_tool_not_by_its_name() {
        FakeProviderImpl provider = new FakeProviderImpl("fake",
            req -> FakeProviderImpl.reply(
                List.of(new ContentBlock.Text(
                    VALID.replace("- src/Main.java: prints lighthouse instead of harbor", "- README.md: read"))),
                StopReasonEnum.STOP));
        List<Message> msgs = editing("a/B.java");
        LoopSnapshot state =
            new LoopSnapshot(msgs, model(8_000), Usage.ZERO, 0, root, false, CancelToken.none(), Optional.empty());
        String written = new ModelSummarizerImpl(() -> provider, registry(ToolEffectEnum.WRITE_FS)).summarize(msgs,
            state, CancelToken.none());
        assertThat(written).contains("- a/B.java: changed");
        String readOnly = new ModelSummarizerImpl(() -> provider, registry(ToolEffectEnum.READ_ONLY)).summarize(msgs,
            state, CancelToken.none());
        assertThat(readOnly).doesNotContain("a/B.java");
    }

    private List<Message> compact(CompactingHooks hooks) {
        return hooks.compact(List.copyOf(context),
            new LoopSnapshot(context, model(100), Usage.ZERO, 0, root, false, CancelToken.none(), leaf), true);
    }

    @Test
    void a_summary_missing_sections_twice_compacts_nothing() {
        List<ChatRequest> requests = new ArrayList<>();
        String bad = without("## Decisions");
        List<Message> out = compact(hooks(List.of(bad, bad), requests));
        assertThat(out).isEqualTo(context);
        assertThat(requests).hasSize(2);
        assertThat(text(requests.get(1))).contains("rejected: missing section ## Decisions");
        assertThat(store.readAll(session)).noneMatch(SessionEntry.CompactionEntry.class::isInstance);
    }

    @Test
    void an_accepted_summary_has_six_sections_and_lists_the_edited_path() {
        List<ChatRequest> requests = new ArrayList<>();
        String forgotFile = VALID.replace("- src/Main.java: prints lighthouse instead of harbor", "- README.md: read");
        List<Message> out = compact(hooks(List.of(without("## Next step"), forgotFile), requests));
        assertThat(out).isNotEqualTo(context);
        assertThat(text(requests.get(0))).contains("## Files touched", "merge it into this one");
        SessionEntry.CompactionEntry c =
            store.readAll(session).stream().flatMap(e -> e instanceof SessionEntry.CompactionEntry x
                ? java.util.stream.Stream.of(x) : java.util.stream.Stream.empty()).findFirst().orElseThrow();
        assertThat(c.summary()).contains(SummaryValidator.SECTIONS.toArray(String[]::new))
            .contains("- src/Main.java: changed");
    }

    @Test
    void the_summarizer_throws_after_a_second_rejection() {
        FakeProviderImpl provider = new FakeProviderImpl("fake",
            req -> FakeProviderImpl.reply(List.of(new ContentBlock.Text("just prose")), StopReasonEnum.STOP));
        List<Message> msgs = editing("a.txt");
        assertThatThrownBy(() -> new ModelSummarizerImpl(() -> provider, new ToolRegistry()).summarize(msgs,
            new LoopSnapshot(msgs, model(8_000), Usage.ZERO, 0, root, false, CancelToken.none(), Optional.empty()),
            CancelToken.none())).isInstanceOf(IllegalStateException.class).hasMessageContaining("summary rejected");
    }

    private static String text(ChatRequest r) {
        return ((ContentBlock.Text)((UserMessage)r.messages().get(0)).content().get(0)).text();
    }
}
