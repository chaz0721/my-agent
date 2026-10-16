/* pai-next — Apache-2.0 */
package dev.pai.ext.tasks;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.SessionAccess;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.ModelRef;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class TasksExtensionTest {

    private static final ModelRef MODEL =
        new ModelRef("fake", "m", URI.create("http://fake.invalid"), 1000, 100, Set.of(ModelRef.CapabilityEnum.TOOLS));

    /** Entries in append order; the branch is the whole list. */
    private final List<SessionEntry.CustomEntry> entries = new ArrayList<>();

    private final SessionAccess session = new SessionAccess() {
        @Override
        public Optional<String> append(String customType, JsonNode data) {
            String id = "e" + entries.size();
            entries.add(new SessionEntry.CustomEntry(id, Optional.empty(), Instant.EPOCH, customType, data));
            return Optional.of(id);
        }

        @Override
        public Optional<SessionEntry.CustomEntry> latest(String customType) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).customType().equals(customType)) {
                    return Optional.of(entries.get(i));
                }
            }
            return Optional.empty();
        }
    };

    private final ExtensionHost host = new ExtensionLoader().install(List.of(new TasksExtension()),
        ExtensionHost.Settings.of(Path.of("h"), Path.of("c")).withSession(session));

    private static JsonNode json(String s) throws JsonProcessingException {
        return Json.mapper().readTree(s);
    }

    private ToolResult call(String tool, String args) throws Exception {
        Tool t = host.tools().get(tool).orElseThrow();
        return t.execute(json(args), null);
    }

    private static String text(ToolResult r) {
        return ((ContentBlock.Text)r.content().get(0)).text();
    }

    private String system() {
        ChatRequest req = new ChatRequest(MODEL, Optional.of("base"), List.of(UserMessage.text("hi")), List.of(),
            ChatOptions.defaults());
        return host.hooks().prepareRequest(req, null).systemPrompt().orElseThrow();
    }

    private static final String TWO = "{\"items\":[{\"id\":\"1\",\"content\":\"Edit A\",\"status\":\"completed\"},"
        + "{\"id\":\"2\",\"content\":\"Run tests\",\"status\":\"in_progress\",\"activeForm\":\"Running tests\"}]}";

    @Test
    void every_write_appends_a_tasks_entry_and_read_returns_the_latest() throws Exception {
        assertThat(text(call("todo_read", "{}"))).isEqualTo("The task list is empty.");
        assertThat(
            text(call("todo_write", "{\"items\":[{\"id\":\"1\",\"content\":\"Edit A\",\"status\":\"pending\"}]}")))
            .contains("0/1 completed", "[ ] 1. Edit A");
        assertThat(text(call("todo_write", TWO))).contains("1/2 completed", "[x] 1. Edit A", "[>] 2. Run tests");
        assertThat(entries).hasSize(2).allSatisfy(e -> assertThat(e.customType()).isEqualTo("tasks"));
        assertThat(entries.get(1).data().toString()).contains("\"activeForm\":\"Running tests\"");
        assertThat(text(call("todo_read", "{}"))).contains("[>] 2. Run tests");
        assertThat(host.tools().get("todo_write").orElseThrow().concurrencySafe()).isFalse();
    }

    @Test
    void invalid_lists_are_refused_and_change_nothing() throws Exception {
        String twoRunning = "{\"items\":[{\"id\":\"1\",\"content\":\"a\",\"status\":\"in_progress\"},"
            + "{\"id\":\"2\",\"content\":\"b\",\"status\":\"in_progress\"}]}";
        ToolResult r = call("todo_write", twoRunning);
        assertThat(r.isError()).isTrue();
        assertThat(text(r)).contains("only one item may be in_progress");
        assertThat(text(call("todo_write", "{\"items\":[{\"id\":\"1\",\"content\":\"a\",\"status\":\"done\"}]}")))
            .contains("status must be");
        assertThat(text(call("todo_write", "{\"items\":[{\"id\":\"1\",\"content\":\"a\",\"status\":\"pending\"},"
            + "{\"id\":\"1\",\"content\":\"b\",\"status\":\"pending\"}]}"))).contains("duplicate id 1");
        assertThat(text(
            call("todo_write", "{\"items\":[{\"id\":\"1\",\"content\":\"a\\u001b[2K\",\"status\":" + "\"pending\"}]}")))
            .contains("control characters");
        assertThat(text(call("todo_write", "{\"items\":\"x\"}"))).contains("items must be an array");
        assertThat(entries).isEmpty();
    }

    @Test
    void a_non_empty_list_is_injected_with_the_guidance() throws Exception {
        assertThat(system()).isEqualTo("base\n\n<tasks>\n" + TodoTools.GUIDANCE + "\n</tasks>");
        call("todo_write", TWO);
        assertThat(system()).startsWith("base\n\n<tasks>\n" + TodoTools.GUIDANCE).contains("[>] 2. Run tests")
            .endsWith("</tasks>");
    }

    @Test
    void the_section_stays_within_its_budget() throws Exception {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < TodoList.MAX_ITEMS; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":\"").append(i).append("\",\"content\":\"")
                .append("x".repeat(80)).append("\",\"status\":\"pending\"}");
        }
        String section = TasksExtension.section(TodoList.parse(json(sb.append("]}").toString())));
        assertThat(section.length()).isLessThanOrEqualTo(TasksExtension.MAX_SECTION);
        assertThat(section).contains(" more\n</tasks>").startsWith(TasksExtension.OPEN);
    }

    @Test
    void the_todo_command_shows_and_clears_the_list() throws Exception {
        call("todo_write", TWO);
        assertThat(host.command("todo").orElseThrow().run("", null).output()).contains("1/2 completed");
        assertThat(host.command("todo").orElseThrow().run("clear", null).output()).isEqualTo("todo: list cleared");
        assertThat(host.command("todo").orElseThrow().run("", null).output()).isEqualTo("The task list is empty.");
        assertThat(system()).isEqualTo("base\n\n" + TasksExtension.section(TodoList.EMPTY));
        assertThat(entries).hasSize(2);
    }
}
