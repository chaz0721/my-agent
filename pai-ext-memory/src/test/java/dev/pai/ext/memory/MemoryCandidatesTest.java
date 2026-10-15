/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.ext.CommandResult;
import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.ext.UserInteraction;
import dev.pai.core.loop.AgentEvent;
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
        Deque<String> queue = new ArrayDeque<>(List.of(answers));
        return new UserInteraction() {
            @Override
            public String ask(String prompt, List<String> choices) {
                prompts.add(prompt);
                return queue.isEmpty() ? "" : queue.poll();
            }

            @Override
            public boolean confirm(String prompt) {
                prompts.add(prompt);
                return review;
            }
        };
    }

    private ExtensionHost host(Optional<UserInteraction> ui) {
        return new ExtensionLoader().install(List.of(new MemoryExtension()),
            new ExtensionHost.Settings(home, cwd, true, ui, notices::add));
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
        c.offer(SUMMARY);
        c.offer(SUMMARY);
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
    void without_a_terminal_only_the_count_is_shown() {
        ExtensionHost h = host(Optional.empty());
        h.dispatch(compacted(Optional.of(SUMMARY)));
        h.dispatch(new SessionEvent.SessionEnded(new SessionId("s")));
        assertThat(stored()).isEmpty();
        assertThat(notices).contains("3 memory candidates from this session not saved (no terminal to review them)");
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
