/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import dev.pai.core.ext.UserInteraction;

/**
 * Memory candidates of the current session: the {@code ## Decisions} lines of each compaction summary, kept in memory
 * until the user reviews them. Nothing is stored without a yes; without a terminal nothing is stored at all.
 */
final class MemoryCandidates {

    static final String DECISIONS = "## Decisions";
    static final int MAX_CHARS = 200;
    static final List<String> CHOICES = List.of("y", "n", "e");

    private static final String HEADING = "## ";
    private static final String BULLET = "- ";
    private static final String LABEL = "decision:";

    private final Set<String> pending = new LinkedHashSet<>();

    /** Adds the decision lines of a summary; lines that look like secrets are dropped. */
    synchronized void offer(String summary) {
        boolean in = false;
        for (String raw : summary.lines().toList()) {
            String l = raw.strip();
            if (l.startsWith(HEADING)) {
                in = DECISIONS.equals(l);
                continue;
            }
            String text = l.startsWith(BULLET) || l.startsWith("* ") ? l.substring(2).strip() : l;
            if (text.toLowerCase(Locale.ROOT).startsWith(LABEL)) {
                text = text.substring(LABEL.length()).strip();
            }
            if (in && !text.isEmpty() && !"none".equals(text.toLowerCase(Locale.ROOT).replace(".", ""))
                && SecretFilter.detect(text).isEmpty()) {
                pending.add(text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS - 3) + "...");
            }
        }
    }

    synchronized List<String> pending() {
        return List.copyOf(pending);
    }

    /**
     * Result of a review.
     *
     * @param saved candidates stored
     * @param editable candidates stored for the user to reword in the memory file
     * @param offered candidates there were
     */
    record Review(int saved, int editable, int offered) {
    }

    /**
     * Asks about every pending candidate and stores the accepted ones; all candidates are cleared afterwards, also when
     * the user declines the review. {@code e} stores the candidate so that the user can reword it in the file.
     *
     * @param ui the terminal, empty when there is none (then nothing is stored)
     * @param save stores one accepted candidate
     * @return what happened
     */
    synchronized Review review(Optional<UserInteraction> ui, Consumer<String> save) {
        List<String> all = new ArrayList<>(pending);
        pending.clear();
        if (all.isEmpty() || ui.isEmpty()) {
            return new Review(0, 0, all.size());
        }
        if (!ui.get().confirm(all.size() + " memory candidates from this session, review?")) {
            return new Review(0, 0, all.size());
        }
        int saved = 0;
        int editable = 0;
        for (String c : all) {
            String answer =
                ui.get().ask("Remember: " + c + "\n(y = save, n = skip, e = save and edit in the file)", CHOICES);
            if ("y".equals(answer) || "e".equals(answer)) {
                save.accept(c);
                saved++;
                editable += "e".equals(answer) ? 1 : 0;
            }
        }
        return new Review(saved, editable, all.size());
    }
}
