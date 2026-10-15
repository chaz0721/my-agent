/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.ext.CommandResult;
import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.SessionAccess;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.ext.UserInteraction;
import dev.pai.core.loop.AgentEvent;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryCandidatesTest {

    static final String SUMMARY =
        String.join("\n", "## Task", "x", "## Decisions", "- use JUnit 5 for tests: the project already does", "- none",
            "- token = sk-abcdefghijklmnopqrstuvwx", "- " + "long ".repeat(60),
            "* Decision: keep one module: small project", "## Open issues", "- use Gradle (not a decision)");

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private final List<String> notices = new ArrayList<>();
    private final List<String> prompts = new ArrayList<>();

    private UserInteraction answering(boolean review, String... answers) {
        return answering(review ? "y" : "n", answers);
    }

    /** {@code review} answers the review question ("" for no answer), {@code answers} the candidates in turn. */
    private UserInteraction answering(String review, String... answers) {
        Deque<String> queue = new ArrayDeque<>(List.of(answers));
        return new UserInteraction() {
            @Override
            public String ask(String prompt, List<String> choices) {
                prompts.add(prompt);
                if (prompt.endsWith("review?")) {
                    return review;
                }
                return queue.isEmpty() ? "" : queue.poll();
            }

            @Override
            public boolean confirm(String prompt) {
                prompts.add(prompt);
                return "y".equals(review);
            }
        };
    }

    /** One session's custom entries, as the host would keep them along the branch. */
    private final Map<String, SessionEntry.CustomEntry> entries = new HashMap<>();
    private final SessionAccess session = new SessionAccess() {
        @Override
        public Optional<String> append(String customType, JsonNode data) {
            entries.put(customType,
                new SessionEntry.CustomEntry("e" + entries.size(), Optional.empty(), Instant.EPOCH, customType, data));
            return Optional.of("e");
        }

        @Override
        public Optional<SessionEntry.CustomEntry> latest(String customType) {
            return Optional.ofNullable(entries.get(customType));
        }
    };

    private ExtensionHost host(Optional<UserInteraction> ui) {
        return new ExtensionLoader().install(List.of(new MemoryExtension()),
            new ExtensionHost.Settings(home, cwd, true, ui, notices::add).withSession(session));
    }

    private static AgentEvent.ContextCompacted compacted(Optional<String> summary) {
        return new AgentEvent.ContextCompacted(10, 4, AgentEvent.ContextCompacted.KindEnum.SUMMARY, summary);
    }

    private List<MemoryEntry> stored() {
        return new MemoryStore(home, cwd).list();
    }

    @Test
    void decision_lines_become_candidates_without_secrets_and_cut_to_200() {
        MemoryCandidates c = new MemoryCandidates();
        c.offer(SUMMARY, Set.of());
        c.offer(SUMMARY, Set.of());
        assertThat(c.pending()).hasSize(3);
        assertThat(c.pending().get(0)).isEqualTo("use JUnit 5 for tests: the project already does");
        assertThat(c.pending().get(1)).hasSize(MemoryCandidates.MAX_CHARS).endsWith("...");
        assertThat(c.pending().get(2)).isEqualTo("keep one module: small project");
    }

    @Test
    void at_session_end_yes_saves_and_no_skips() {
        ExtensionHost h = host(Optional.of(answering(true, "y", "n", "e")));
        h.dispatch(compacted(Optional.of(SUMMARY)));
        h.dispatch(compacted(Optional.empty()));
        h.dispatch(new SessionEvent.SessionEnded(new SessionId("s")));
        assertThat(prompts.get(0)).isEqualTo("3 memory candidates from this session, review?");
        assertThat(stored()).extracting(MemoryEntry::text)
            .containsExactly("use JUnit 5 for tests: the project already does", "keep one module: small project");
        assertThat(stored()).allMatch(e -> MemoryStore.PROJECT.equals(e.scope()));
        assertThat(notices).anyMatch(n -> n.startsWith("memory: saved 2 of 3 candidates; reword them in"));
        h.dispatch(new SessionEvent.SessionEnded(new SessionId("s")));
        assertThat(prompts).hasSize(4);
    }

    @Test
    void declining_the_review_saves_nothing() {
        ExtensionHost h = host(Optional.of(answering(false)));
        h.dispatch(compacted(Optional.of(SUMMARY)));
        h.dispatch(new SessionEvent.SessionEnded(new SessionId("s")));
        assertThat(stored()).isEmpty();
        assertThat(notices).contains("memory: saved 0 of 3 candidates");
    }

    @Test
    void no_answer_to_the_review_question_leaves_the_candidates_pending_and_rejects_nothing() {
        ExtensionHost h = host(Optional.of(answering("")));
        h.dispatch(compacted(Optional.of(SUMMARY)));
        h.dispatch(new SessionEvent.SessionEnded(new SessionId("s")));
        assertThat(notices).contains("3 memory candidates left unreviewed");
        assertThat(stored()).isEmpty();
        assertThat(entries).doesNotContainKey(MemoryExtension.REJECTED_TYPE);
        assertThat(h.command("memory").orElseThrow().run("review", null).output())
            .isEqualTo("3 memory candidates left unreviewed");
    }

    @Test
    void an_explicit_no_to_the_review_rejects_the_whole_batch() {
        ExtensionHost h = host(Optional.of(answering("n")));
        h.dispatch(new SessionEvent.SessionStarted(new SessionId("s"), false));
        h.dispatch(compacted(Optional.of(SUMMARY)));
        h.dispatch(new SessionEvent.SessionEnded(new SessionId("s")));
        assertThat(entries.get(MemoryExtension.REJECTED_TYPE).data().path("keys")).hasSize(3);
        prompts.clear();
        h.dispatch(compacted(Optional.of(SUMMARY)));
        h.dispatch(new SessionEvent.SessionEnded(new SessionId("s")));
        assertThat(prompts).isEmpty();
    }

    @Test
    void without_a_terminal_only_the_count_is_shown() {
        ExtensionHost h = host(Optional.empty());
        h.dispatch(compacted(Optional.of(SUMMARY)));
        h.dispatch(new SessionEvent.SessionEnded(new SessionId("s")));
        assertThat(stored()).isEmpty();
        assertThat(notices).contains("3 memory candidates from this session not saved (no terminal to review them)");
    }

    @Test
    void a_second_compaction_that_carries_the_old_decisions_asks_nothing_again() {
        ExtensionHost h = host(Optional.of(answering(true, "y", "n", "y")));
        h.dispatch(new SessionEvent.SessionStarted(new SessionId("s"), false));
        h.dispatch(compacted(Optional.of(SUMMARY)));
        assertThat(h.command("memory").orElseThrow().run("review", null).output())
            .isEqualTo("memory: saved 2 of 3 candidates");
        String merged = SUMMARY.replace("* Decision: keep one module",
            "- Use JUnit 5 for tests: the project already " + "does.\n* Decision: keep one module");
        h.dispatch(compacted(Optional.of(merged)));
        h.dispatch(compacted(Optional.of(merged)));
        h.dispatch(new SessionEvent.SessionEnded(new SessionId("s")));
        assertThat(prompts).hasSize(4);
        assertThat(stored()).hasSize(2);
        assertThat(entries.get(MemoryExtension.REJECTED_TYPE).data().path("keys")).hasSize(1);
    }

    @Test
    void after_a_resume_a_rejected_candidate_is_not_offered_again() {
        ExtensionHost first = host(Optional.of(answering(true, "n", "n", "n")));
        first.dispatch(new SessionEvent.SessionStarted(new SessionId("s"), false));
        first.dispatch(compacted(Optional.of(SUMMARY)));
        first.dispatch(new SessionEvent.SessionEnded(new SessionId("s")));
        assertThat(stored()).isEmpty();
        prompts.clear();
        ExtensionHost resumed = host(Optional.of(answering(true, "y", "y", "y")));
        resumed.dispatch(new SessionEvent.SessionStarted(new SessionId("s"), true));
        resumed.dispatch(compacted(Optional.of(SUMMARY + "\n## Decisions\n- a new decision: reason")));
        resumed.dispatch(new SessionEvent.SessionEnded(new SessionId("s")));
        assertThat(prompts.get(0)).isEqualTo("1 memory candidates from this session, review?");
        assertThat(stored()).extracting(MemoryEntry::text).containsExactly("a new decision: reason");
    }

    @Test
    void keys_ignore_case_blanks_and_trailing_punctuation() {
        assertThat(MemoryCandidates.key("  Use  JUnit 5. ")).isEqualTo(MemoryCandidates.key("use junit 5"));
        assertThat(MemoryCandidates.key("use junit 5")).isNotEqualTo(MemoryCandidates.key("use junit 4"));
        MemoryCandidates c = new MemoryCandidates();
        c.offer("## Decisions\n- " + "\ud83d\ude00".repeat(300), Set.of());
        String cut = c.pending().get(0);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(MemoryCandidates.MAX_CHARS);
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 4))).isFalse();
    }

    @Test
    void memory_review_asks_now() {
        ExtensionHost h = host(Optional.of(answering(true, "y", "y", "y")));
        h.dispatch(compacted(Optional.of(SUMMARY)));
        CommandResult r = h.command("memory").orElseThrow().run("review", null);
        assertThat(r.output()).isEqualTo("memory: saved 3 of 3 candidates");
        assertThat(h.command("memory").orElseThrow().run("review", null).output())
            .isEqualTo("no memory candidates in this session");
    }
}
