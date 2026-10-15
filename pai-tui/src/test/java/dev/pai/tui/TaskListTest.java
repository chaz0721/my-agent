/* pai-next — Apache-2.0 */
package dev.pai.tui;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;

class TaskListTest {

    private static JsonNode json(String s) throws JsonProcessingException {
        return Json.mapper().readTree(s);
    }

    @Test
    void shows_progress_and_the_active_form_of_the_running_item() throws JsonProcessingException {
        assertThat(TaskList.lines(json("{\"items\":[{\"id\":\"1\",\"content\":\"Edit A\",\"status\":\"completed\"},"
            + "{\"id\":\"2\",\"content\":\"Edit B\",\"status\":\"in_progress\",\"activeForm\":\"Editing B\"},"
            + "{\"id\":\"3\",\"content\":\"Run tests\",\"status\":\"pending\"}]}")))
            .containsExactly("tasks 1/3", "  [x] Edit A", "  [>] Editing B", "  [ ] Run tests");
    }

    @Test
    void an_empty_list_hides_the_sidebar_and_long_lists_are_cut() throws JsonProcessingException {
        assertThat(TaskList.lines(json("{\"items\":[]}"))).isEmpty();
        assertThat(TaskList.lines(json("{}"))).isEmpty();
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < TaskList.MAX_ITEMS + 2; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"content\":\"t").append(i).append("\",\"status\":\"pending\"}");
        }
        assertThat(TaskList.lines(json(sb.append("]}").toString()))).hasSize(TaskList.MAX_ITEMS + 2).last()
            .isEqualTo("  ... 2 more");
    }

    @Test
    void control_characters_from_the_model_are_made_visible() throws JsonProcessingException {
        assertThat(TaskList.lines(json("{\"items\":[{\"content\":\"a\\u001b[2Kb\\nc\",\"status\":\"pending\"}]}")))
            .contains("  [ ] a\\x1b[2Kb c");
    }
}
