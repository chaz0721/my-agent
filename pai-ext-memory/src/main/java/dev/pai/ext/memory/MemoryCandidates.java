/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import dev.pai.core.ext.UserInteraction;

/**
 * Memory candidates of the current session: the {@code ## Decisions} lines of each compaction summary, kept in memory
 * until the user reviews them. Nothing is stored without a yes; without a terminal nothing is stored at all. Summaries
 * merge their predecessors, so the same decision comes back with every compaction: a candidate whose {@link #key
 * normalized text} is already remembered, already pending, or was turned down in this session is not offered again.
 */
final class MemoryCandidates {

    static final String DECISIONS = "## Decisions";
    static final int MAX_CHARS = 200;
    static final List<String> CHOICES = List.of("y", "n", "e");
    static final List<String> YES_NO = List.of("y", "n");

    private static final String HEADING = "## ";
    private static final String BULLET = "- ";
    private static final String STAR = "* ";
    private static final String LABEL = "decision:";

    /** Pending candidates by key, in the order offered. */
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final Set<String> rejected = new HashSet<>();

    /** The comparison key of a candidate: trimmed, blanks folded, trailing punctuation dropped, lower case, hashed. */
    static String key(String text) {
        String t = text.strip().replaceAll("\\s+", " ").replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+$", "");
        return MemoryStore.hash(t.toLowerCase(Locale.ROOT));
    }

    /**
     * Adds the decision lines of a summary; lines that look like secrets, and lines whose key is in {@code known} (the
     * remembered facts), pending or rejected, are dropped.
     */
    synchronized void offer(String summary, Set<String> known) {
        boolean in = false;
        for (String raw : summary.lines().toList()) {
            String l = raw.strip();
            if (l.startsWith(HEADING)) {
                in = DECISIONS.equals(l);
                continue;
            }
            String text = l.startsWith(BULLET) || l.startsWith(STAR) ? l.substring(2).strip() : l;
            if (text.toLowerCase(Locale.ROOT).startsWith(LABEL)) {
                text = text.substring(LABEL.length()).strip();
            }
            if (!in || text.isEmpty() || "none".equals(text.toLowerCase(Locale.ROOT).replace(".", ""))
                || SecretFilter.detect(text).isPresent()) {
                continue;
            }
            String candidate = MemoryFile.cut(text, MAX_CHARS);
            String k = key(candidate);
            if (!known.contains(k) && !rejected.contains(k)) {
                pending.putIfAbsent(k, candidate);
            }
        }
    }

    synchronized List<String> pending() {
        return List.copyOf(pending.values());
    }

    /** Keys turned down in this session. */
    synchronized Set<String> rejected() {
        return Set.copyOf(rejected);
    }

    /** Starts a session: nothing pending, and the keys turned down earlier in it (after a resume). */
    synchronized void sessionStarted(Collection<String> rejectedBefore) {
        pending.clear();
        rejected.clear();
        rejected.addAll(rejectedBefore);
    }

    /**
     * Result of a review.
     *
     * @param saved candidates stored
     * @param editable candidates stored for the user to reword in the memory file
     * @param offered candidates there were
     * @param newlyRejected candidates turned down in this review
     * @param unreviewed candidates left pending because the review question got no answer
     */
    record Review(int saved, int editable, int offered, int newlyRejected, int unreviewed) {
    }

    /**
     * Asks about every pending candidate not remembered meanwhile and stores the accepted ones; all candidates are
     * cleared afterwards. {@code n}, and an explicit no to the whole review, turn candidates down for the rest of the
     * session; no answer to the review question (end of input, interrupt) leaves them pending and turns nothing down.
     * {@code e} stores the candidate so that the user can reword it in the file.
     *
     * @param ui the terminal, empty when there is none (then nothing is stored)
     * @param known keys of the remembered facts
     * @param save stores one accepted candidate
     * @return what happened
     */
    synchronized Review review(Optional<UserInteraction> ui, Set<String> known, Consumer<String> save) {
        pending.keySet().removeIf(known::contains);
        List<Map.Entry<String, String>> all = new ArrayList<>(pending.entrySet());
        if (all.isEmpty() || ui.isEmpty()) {
            pending.clear();
            return new Review(0, 0, all.size(), 0, 0);
        }
        String go = ui.get().ask(all.size() + " memory candidates from this session, review?", YES_NO);
        if (go.isEmpty()) {
            return new Review(0, 0, all.size(), 0, all.size());
        }
        pending.clear();
        int before = rejected.size();
        if ("n".equals(go)) {
            all.forEach(e -> rejected.add(e.getKey()));
            return new Review(0, 0, all.size(), rejected.size() - before, 0);
        }
        int saved = 0;
        int editable = 0;
        for (Map.Entry<String, String> c : all) {
            String answer = ui.get()
                .ask("Remember: " + c.getValue() + "\n(y = save, n = skip, e = save and edit in the file)", CHOICES);
            if ("y".equals(answer) || "e".equals(answer)) {
                save.accept(c.getValue());
                saved++;
                editable += "e".equals(answer) ? 1 : 0;
            } else if ("n".equals(answer)) {
                rejected.add(c.getKey());
            }
        }
        return new Review(saved, editable, all.size(), rejected.size() - before, 0);
    }
}
