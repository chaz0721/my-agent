/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionTree;
import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.SystemMessage;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.UserMessage;

/**
 * Renders a session tree as indented ASCII: one line per entry with the first six id characters, the kind and up to
 * sixty characters of content. A chain stays at one indent; each branch of a fork is indented. Entries on the current
 * branch are marked {@code *}, the leaf {@code <- leaf}.
 */
public final class SessionTreeView {

    /** Characters of entry content shown per line. */
    public static final int CONTENT_CHARS = 60;
    /** Characters of the entry id shown per line. */
    public static final int ID_CHARS = 6;

    private static final String INDENT = "  ";

    private SessionTreeView() {}

    /**
     * Renders the tree.
     *
     * @param tree the tree
     * @param leaf the current leaf
     * @return lines joined with newlines; a note when the session is empty
     */
    public static String render(SessionTree tree, Optional<String> leaf) {
        if (tree.roots().isEmpty()) {
            return "(empty session)";
        }
        Set<String> onPath = new HashSet<>();
        leaf.ifPresent(l -> tree.path(l).forEach(e -> onPath.add(e.id())));
        StringBuilder sb = new StringBuilder();
        Deque<Item> stack = new ArrayDeque<>();
        List<SessionEntry> roots = tree.roots();
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.push(new Item(roots.get(i), 0, roots.size() > 1));
        }
        while (!stack.isEmpty()) {
            Item it = stack.pop();
            SessionEntry e = it.entry();
            sb.append(INDENT.repeat(it.depth())).append(it.branch() ? "+- " : it.depth() > 0 ? "   " : "")
                .append(onPath.contains(e.id()) ? "* " : "  ").append(shortId(e.id())).append(' ').append(describe(e));
            List<String> labels = tree.labels(e.id());
            if (!labels.isEmpty()) {
                sb.append("  [").append(String.join(", ", labels)).append(']');
            }
            if (leaf.isPresent() && leaf.get().equals(e.id())) {
                sb.append("  <- leaf");
            }
            sb.append('\n');
            List<SessionEntry> children = tree.children(e.id());
            boolean fork = children.size() > 1;
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(new Item(children.get(i), fork ? it.depth() + 1 : it.depth(), fork));
            }
        }
        return sb.toString().stripTrailing();
    }

    private static String shortId(String id) {
        return id.length() <= ID_CHARS ? id : id.substring(0, ID_CHARS);
    }

    static String describe(SessionEntry e) {
        return switch (e) {
            case SessionEntry.MessageEntry m -> switch (m.message()) {
                case UserMessage u -> "user: " + content(texts(u.content()));
                case AssistantMessage a -> "assistant: " + content(a.text().isBlank() ? a.toolCalls().stream()
                    .map(ContentBlock.ToolCall::name).collect(Collectors.joining(", ", "[", "]")) : a.text());
                case ToolResultMessage r -> "tool " + r.toolName() + ": " + content(texts(r.content()));
                case SystemMessage s -> "system: " + content(s.text());
            };
            case SessionEntry.ModelChangeEntry m -> "model -> " + m.providerId() + "/" + m.modelId();
            case SessionEntry.CompactionEntry c -> "compaction (" + c.tokensBefore() + " tokens, kept from "
                + shortId(c.firstKeptEntryId()) + "): " + content(c.summary());
            case SessionEntry.LabelEntry l -> "label " + shortId(l.targetId()) + ": " + content(l.label());
            case SessionEntry.CustomEntry c -> "custom " + c.customType();
        };
    }

    private static String texts(List<ContentBlock> blocks) {
        return blocks.stream().filter(ContentBlock.Text.class::isInstance).map(b -> ((ContentBlock.Text)b).text())
            .collect(Collectors.joining(" "));
    }

    private static String content(String s) {
        return ToolCallSummaries.cut(s.strip(), CONTENT_CHARS);
    }

    private record Item(SessionEntry entry, int depth, boolean branch) {
    }
}
