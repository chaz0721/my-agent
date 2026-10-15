/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Renders the task list the tasks extension keeps in its session entries ({@value #CUSTOM_TYPE}:
 * {@code {"items":[{"content","status","activeForm"}]}}) as sidebar lines: a header with the progress, then one line
 * per item, the item in progress shown by its active form. Control characters are made visible: the text comes from the
 * model.
 */
public final class TaskList {

    /** Custom entry type the tasks extension writes. */
    public static final String CUSTOM_TYPE = "tasks";
    /** Tool of the tasks extension that rewrites the list. */
    public static final String TOOL = "todo_write";
    /** Items shown before the rest is summarized. */
    public static final int MAX_ITEMS = 8;

    private TaskList() {}

    /**
     * Turns entry data into sidebar lines.
     *
     * @param data the data of the latest tasks entry
     * @return the lines, empty when the list is empty
     */
    public static List<String> lines(JsonNode data) {
        JsonNode items = data.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return List.of();
        }
        int done = 0;
        List<String> body = new ArrayList<>();
        for (JsonNode item : items) {
            String status = item.path("status").asText();
            if ("completed".equals(status)) {
                done++;
            }
            if (body.size() < MAX_ITEMS) {
                body.add(line(item, status));
            }
        }
        List<String> out = new ArrayList<>();
        out.add("tasks " + done + "/" + items.size());
        out.addAll(body);
        if (items.size() > MAX_ITEMS) {
            out.add("  ... " + (items.size() - MAX_ITEMS) + " more");
        }
        return out;
    }

    private static String line(JsonNode item, String status) {
        String content = item.path("content").asText();
        String text = switch (status) {
            case "completed" -> "  [x] " + content;
            case "in_progress" -> "  [>] " + item.path("activeForm").asText(content);
            default -> "  [ ] " + content;
        };
        return TerminalInteractionImpl.visible(text).replace('\n', ' ').replace('\t', ' ');
    }
}
