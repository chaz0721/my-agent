/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.util.ArrayList;
import java.util.List;

import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.ToolResultMessage;

/**
 * Deterministic, model-free trimming of early tool results: a projection recomputed for each request, never written to
 * the session. Calls, call ids, tool names and error flags stay, so every tool call still has its result.
 */
public final class MicroCompactor {

    /** Start of every placeholder; a result that already is one is never trimmed again. */
    public static final String PLACEHOLDER_PREFIX = "[tool result trimmed by pai: ";

    /**
     * Replaces the content of each {@link ToolResultMessage} before the last {@code keepRecentTurns} assistant turns
     * whose text exceeds {@code maxToolResultChars}, or which holds an image, with one placeholder text block.
     *
     * @param messages the context
     * @param p the policy
     * @return a new list, or the very same {@code messages} instance when nothing was replaced
     */
    public List<Message> apply(List<Message> messages, MicroCompactionPolicy p) {
        int end = recentStart(messages, p.keepRecentTurns());
        List<Message> out = null;
        for (int i = 0; i < end; i++) {
            if (messages.get(i) instanceof ToolResultMessage r && trims(r, p)) {
                if (out == null) {
                    out = new ArrayList<>(messages);
                }
                out.set(i, new ToolResultMessage(r.toolCallId(), r.toolName(),
                    List.of(new ContentBlock.Text(placeholder(r))), r.isError(), r.timestamp()));
            }
        }
        return out == null ? messages : out;
    }

    /** Index of the first message of the kept recent turns; {@code 0} when there are not that many turns. */
    private static int recentStart(List<Message> messages, int keepRecentTurns) {
        if (keepRecentTurns == 0) {
            return messages.size();
        }
        int seen = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage) {
                seen++;
                if (seen == keepRecentTurns) {
                    return i;
                }
            }
        }
        return 0;
    }

    private static boolean trims(ToolResultMessage r, MicroCompactionPolicy p) {
        if (p.neverTrim().contains(r.toolName())) {
            return false;
        }
        boolean image = r.content().stream().anyMatch(ContentBlock.Image.class::isInstance);
        String text = text(r);
        return image || text.length() > p.maxToolResultChars() && !text.startsWith(PLACEHOLDER_PREFIX);
    }

    static String placeholder(ToolResultMessage r) {
        String text = text(r);
        long lines = text.isEmpty() ? 0L : text.lines().count();
        return PLACEHOLDER_PREFIX + r.toolName() + ", " + text.length() + " chars, " + lines + " lines; call id "
            + r.toolCallId() + "]";
    }

    private static String text(ToolResultMessage r) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : r.content()) {
            if (b instanceof ContentBlock.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
