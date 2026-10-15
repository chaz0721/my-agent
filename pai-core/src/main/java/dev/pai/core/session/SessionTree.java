/* pai-next — Apache-2.0 */
package dev.pai.core.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.UserMessage;

/**
 * Read-only tree view over the entries of one session: parent links, branches, labels and the context projection used
 * by {@link SessionStore#buildContext}.
 */
public final class SessionTree {

    /**
     * How the text of a message with restored files begins: such messages are derived from the compaction above them,
     * so a later compaction does not keep them.
     */
    public static final String RESTORED_FILES_TAG = "<restored-files>";

    private static final Logger log = LoggerFactory.getLogger(SessionTree.class);

    private final Map<String, SessionEntry> byId;
    private final Map<String, List<SessionEntry>> children;
    private final List<SessionEntry> roots;
    private final Map<String, List<String>> labels;

    private SessionTree(Map<String, SessionEntry> byId, Map<String, List<SessionEntry>> children,
        List<SessionEntry> roots, Map<String, List<String>> labels) {
        this.byId = byId;
        this.children = children;
        this.roots = roots;
        this.labels = labels;
    }

    /**
     * Builds the tree from entries in file order. Entries whose parent is unknown are treated as roots.
     *
     * @param entries the entries
     * @return the tree
     */
    public static SessionTree of(List<SessionEntry> entries) {
        Map<String, SessionEntry> byId = new LinkedHashMap<>(entries.size() * 2);
        for (SessionEntry e : entries) {
            byId.put(e.id(), e);
        }
        Map<String, List<SessionEntry>> children = new LinkedHashMap<>(entries.size() * 2);
        List<SessionEntry> roots = new ArrayList<>();
        Map<String, List<String>> labels = new LinkedHashMap<>();
        for (SessionEntry e : byId.values()) {
            Optional<String> parent = e.parentId().filter(byId::containsKey);
            if (parent.isPresent()) {
                children.computeIfAbsent(parent.get(), k -> new ArrayList<>()).add(e);
            } else {
                roots.add(e);
            }
            if (e instanceof SessionEntry.LabelEntry l) {
                labels.computeIfAbsent(l.targetId(), k -> new ArrayList<>()).add(l.label());
            }
        }
        return new SessionTree(byId, children, roots, labels);
    }

    /**
     * Looks an entry up.
     *
     * @param id entry id
     * @return the entry
     */
    public Optional<SessionEntry> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Resolves a full id or a unique id prefix.
     *
     * @param idOrPrefix id or prefix
     * @return the full id, empty when unknown or ambiguous
     */
    public Optional<String> resolve(String idOrPrefix) {
        if (byId.containsKey(idOrPrefix)) {
            return Optional.of(idOrPrefix);
        }
        List<String> hits = byId.keySet().stream().filter(k -> k.startsWith(idOrPrefix)).toList();
        return hits.size() == 1 && !idOrPrefix.isEmpty() ? Optional.of(hits.get(0)) : Optional.empty();
    }

    /**
     * Returns entries without a known parent, in file order.
     *
     * @return the roots
     */
    public List<SessionEntry> roots() {
        return List.copyOf(roots);
    }

    /**
     * Returns the direct children of an entry, in file order.
     *
     * @param id entry id
     * @return children, possibly empty
     */
    public List<SessionEntry> children(String id) {
        return List.copyOf(children.getOrDefault(id, List.of()));
    }

    /**
     * Returns the labels attached to an entry.
     *
     * @param id entry id
     * @return labels in order of creation
     */
    public List<String> labels(String id) {
        return List.copyOf(labels.getOrDefault(id, List.of()));
    }

    /**
     * Returns the branch from the root to an entry.
     *
     * @param leafId the last entry
     * @return entries root first; empty when the id is unknown
     */
    public List<SessionEntry> path(String leafId) {
        List<SessionEntry> path = new ArrayList<>();
        Optional<String> cursor = Optional.of(leafId);
        while (cursor.isPresent() && byId.containsKey(cursor.get()) && path.size() <= byId.size()) {
            SessionEntry e = byId.get(cursor.get());
            path.add(e);
            cursor = e.parentId();
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * Projects a branch into model context: the latest compaction on the path is replaced by its summary message,
     * followed by the kept entries (without earlier {@linkplain #restoredFiles restored files}) and everything after
     * the compaction.
     *
     * @param leafId the last entry of the branch
     * @return messages with, per message, the entry id it came from (empty for the summary)
     */
    public Projection project(String leafId) {
        List<SessionEntry> path = path(leafId);
        int compactionAt = -1;
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i) instanceof SessionEntry.CompactionEntry) {
                compactionAt = i;
            }
        }
        List<Message> messages = new ArrayList<>();
        List<Optional<String>> ids = new ArrayList<>();
        if (compactionAt < 0) {
            addMessages(path, messages, ids);
            return new Projection(messages, ids);
        }
        SessionEntry.CompactionEntry c = (SessionEntry.CompactionEntry)path.get(compactionAt);
        int keepFrom = compactionAt;
        for (int i = 0; i < compactionAt; i++) {
            if (path.get(i).id().equals(c.firstKeptEntryId())) {
                keepFrom = i;
                break;
            }
        }
        if (keepFrom == compactionAt && !c.firstKeptEntryId().isEmpty()) {
            log.warn("compaction {} keeps from {}, which is not on the branch; keeping nothing before it", c.id(),
                c.firstKeptEntryId());
        }
        messages.add(summaryMessage(c.summary(), c.timestamp()));
        ids.add(Optional.empty());
        addMessages(
            path.subList(keepFrom, compactionAt).stream()
                .filter(e -> !(e instanceof SessionEntry.MessageEntry m && restoredFiles(m.message()))).toList(),
            messages, ids);
        addMessages(path.subList(compactionAt + 1, path.size()), messages, ids);
        return new Projection(messages, ids);
    }

    /**
     * Returns the user message that stands in for compacted history.
     *
     * @param summary the summary text
     * @param timestamp the compaction time
     * @return the message
     */
    public static UserMessage summaryMessage(String summary, Instant timestamp) {
        return new UserMessage(List.of(new ContentBlock.Text(JsonlSessionStoreImpl.SUMMARY_PREFIX + summary)),
            timestamp);
    }

    /**
     * Whether a message carries files restored after a compaction: a user message whose first block is text beginning
     * with {@value #RESTORED_FILES_TAG}.
     *
     * @param m the message
     * @return true for restored files
     */
    public static boolean restoredFiles(Message m) {
        return m instanceof UserMessage u && !u.content().isEmpty() && u.content().get(0) instanceof ContentBlock.Text t
            && t.text().startsWith(RESTORED_FILES_TAG);
    }

    private static void addMessages(List<SessionEntry> entries, List<Message> messages, List<Optional<String>> ids) {
        for (SessionEntry e : entries) {
            if (e instanceof SessionEntry.MessageEntry m) {
                messages.add(m.message());
                ids.add(Optional.of(m.id()));
            }
        }
    }

    /**
     * A branch projected into model context.
     *
     * @param messages the context
     * @param entryIds for each message, the entry it came from; empty for the compaction summary
     */
    public record Projection(List<Message> messages, List<Optional<String>> entryIds) {

        /**
         * Copies the lists and checks they align.
         */
        public Projection {
            messages = List.copyOf(messages);
            entryIds = List.copyOf(entryIds);
            if (messages.size() != entryIds.size()) {
                throw new IllegalArgumentException("messages and entryIds differ in size");
            }
            Objects.requireNonNull(messages, "messages");
        }
    }
}
