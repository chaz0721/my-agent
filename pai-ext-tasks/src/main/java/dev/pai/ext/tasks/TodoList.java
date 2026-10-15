/* pai-next — Apache-2.0 */
package dev.pai.ext.tasks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.Json;

/**
 * The task list as the model wrote it last: parsed and checked from {@code todo_write} arguments, stored as the data of
 * a {@value #CUSTOM_TYPE} session entry ({@code {"items":[...]}}) and rendered for the model and the user.
 *
 * @param items the items in order
 */
record TodoList(List<TodoItem> items) {

    /** Custom entry type of the stored list. */
    static final String CUSTOM_TYPE = "tasks";
    /** Most items a list may have. */
    static final int MAX_ITEMS = 50;
    /** Longest item text accepted. */
    static final int MAX_CONTENT = 300;

    static final TodoList EMPTY = new TodoList(List.of());

    TodoList {
        items = List.copyOf(items);
    }

    /**
     * Parses and checks a list: every item needs a unique {@code id}, a {@code content} and a {@code status}; at most
     * one item may be in progress.
     *
     * @param data an object with an {@code items} array
     * @return the list
     * @throws IllegalArgumentException with a message for the model when the list is invalid
     */
    static TodoList parse(JsonNode data) {
        JsonNode array = data.path("items");
        if (!array.isArray()) {
            throw new IllegalArgumentException("items must be an array");
        }
        if (array.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("at most " + MAX_ITEMS + " items");
        }
        List<TodoItem> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode n : array) {
            TodoItem item = item(n, out.size());
            if (!ids.add(item.id())) {
                throw new IllegalArgumentException("duplicate id " + item.id());
            }
            out.add(item);
        }
        long running = out.stream().filter(i -> i.status() == TodoStatusEnum.IN_PROGRESS).count();
        if (running > 1) {
            throw new IllegalArgumentException("only one item may be in_progress at a time, found " + running);
        }
        return new TodoList(out);
    }

    private static TodoItem item(JsonNode n, int index) {
        String where = "item " + (index + 1) + ": ";
        String id = n.path("id").asText("").strip();
        String content = n.path("content").asText("").strip();
        if (id.isEmpty() || content.isEmpty()) {
            throw new IllegalArgumentException(where + "id and content must be non-empty strings");
        }
        if (content.length() > MAX_CONTENT) {
            throw new IllegalArgumentException(where + "content longer than " + MAX_CONTENT + " characters");
        }
        TodoStatusEnum status = TodoStatusEnum.parse(n.path("status").asText("")).orElseThrow(
            () -> new IllegalArgumentException(where + "status must be pending, in_progress or completed"));
        String active = n.path("activeForm").asText("").strip();
        if (hasControl(id) || hasControl(content) || hasControl(active)) {
            throw new IllegalArgumentException(where + "control characters are not allowed");
        }
        return new TodoItem(id, content, status, active.isEmpty() ? Optional.empty() : Optional.of(active));
    }

    private static boolean hasControl(String s) {
        return s.chars().anyMatch(
            c -> Character.isISOControl(c) || c >= '\u202a' && c <= '\u202e' || c >= '\u2066' && c <= '\u2069');
    }

    /** The stored form. */
    ObjectNode toJson() {
        ObjectNode root = Json.mapper().createObjectNode();
        ArrayNode array = root.putArray("items");
        for (TodoItem i : items) {
            ObjectNode o =
                array.addObject().put("id", i.id()).put("content", i.content()).put("status", i.status().wireName());
            i.activeForm().ifPresent(a -> o.put("activeForm", a));
        }
        return root;
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    long completed() {
        return items.stream().filter(i -> i.status() == TodoStatusEnum.COMPLETED).count();
    }

    /** One line per item: {@code [x]} completed, {@code [>]} in progress, {@code [ ]} pending, then id and content. */
    List<String> lines() {
        return items.stream().map(i -> mark(i.status()) + " " + i.id() + ". " + i.content()).toList();
    }

    /** Lines that fit {@code budget} characters, the rest summarized. */
    String render(int budget) {
        StringBuilder sb = new StringBuilder();
        List<String> lines = lines();
        for (int i = 0; i < lines.size(); i++) {
            String more = "... " + (lines.size() - i) + " more";
            if (sb.length() + lines.get(i).length() + more.length() + 2 > budget) {
                return sb.append(more).toString();
            }
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String mark(TodoStatusEnum s) {
        return switch (s) {
            case COMPLETED -> "[x]";
            case IN_PROGRESS -> "[>]";
            case PENDING -> "[ ]";
        };
    }
}
