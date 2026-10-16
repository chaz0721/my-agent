/* pai-next — Apache-2.0 */
package dev.pai.ext.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.Json;
import dev.pai.model.ToolDefinition;

/**
 * The two tools of the task list. The list lives in session entries ({@link ExtensionContext#appendEntry}), so it
 * follows the branch and survives compaction; {@code todo_write} only touches the session, hence read-only.
 */
final class TodoTools {

    static final String WRITE = "todo_write";
    static final String READ = "todo_read";
    static final String GUIDANCE = "For a task with 3+ steps or 2+ files to change, call todo_write before starting; "
        + "update it as each item is done; keep one item in_progress.";

    private TodoTools() {}

    /** The current list of the branch; a stored list that no longer parses counts as empty. */
    static TodoList current(ExtensionContext ctx) {
        return ctx.latestEntry(TodoList.CUSTOM_TYPE).map(e -> {
            try {
                return TodoList.parse(e.data());
            } catch (IllegalArgumentException ex) {
                ctx.logger().warn("ignoring unreadable task list entry {}: {}", e.id(), ex.getMessage());
                return TodoList.EMPTY;
            }
        }).orElse(TodoList.EMPTY);
    }

    static String summary(TodoList list) {
        if (list.isEmpty()) {
            return "The task list is empty.";
        }
        return "Task list (" + list.completed() + "/" + list.items().size() + " completed):\n"
            + String.join("\n", list.lines());
    }

    /**
     * {@code todo_write(items)}: replaces the whole list.
     */
    static final class WriteTool implements Tool {
        private final ExtensionContext ctx;

        WriteTool(ExtensionContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public ToolDefinition definition() {
            ObjectNode schema = JsonSchema.object()
                .array("items", "The complete list, in order; it replaces the previous one", "object").required("items")
                .build();
            ObjectNode item = Json.mapper().createObjectNode().put("type", "object");
            ObjectNode props = item.putObject("properties");
            props.putObject("id").put("type", "string").put("description", "Short unique id, e.g. 1, 2, 3");
            props.putObject("content").put("type", "string").put("description", "What to do, imperative");
            props.putObject("status").put("type", "string").putArray("enum").add("pending").add("in_progress")
                .add("completed");
            props.putObject("activeForm").put("type", "string").put("description",
                "Present continuous form shown while in progress, e.g. Running the tests");
            item.putArray("required").add("id").add("content").add("status");
            ((ObjectNode)schema.path("properties").path("items")).set("items", item);
            return new ToolDefinition(WRITE, "Write the task list for the current work and update it as you go. "
                + GUIDANCE + " Not for single trivial steps.", schema);
        }

        @Override
        public ToolEffectEnum effect() {
            return ToolEffectEnum.READ_ONLY;
        }

        @Override
        public boolean concurrencySafe() {
            return false;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext tc) {
            TodoList list;
            try {
                list = TodoList.parse(arguments);
            } catch (IllegalArgumentException e) {
                return ToolResult.error("task list not changed: " + e.getMessage());
            }
            ctx.appendEntry(TodoList.CUSTOM_TYPE, list.toJson());
            return ToolResult.text(summary(list));
        }
    }

    /**
     * {@code todo_read()}: returns the current list.
     */
    static final class ReadTool implements Tool {
        private final ExtensionContext ctx;

        ReadTool(ExtensionContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(READ, "Read the current task list.", JsonSchema.object().build());
        }

        @Override
        public ToolEffectEnum effect() {
            return ToolEffectEnum.READ_ONLY;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext tc) {
            return ToolResult.text(summary(current(ctx)));
        }
    }
}
