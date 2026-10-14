/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArgsTest {

    private final ObjectNode node = Json.mapper().createObjectNode().put("path", "a.txt").put("limit", 5)
        .put("whole", 3.0d).put("flag", true).put("bad", "x").putNull("nothing");

    @Test
    void reads_typed_values_and_defaults() {
        Args a = Args.of(node);
        assertThat(a.string("path")).isEqualTo("a.txt");
        assertThat(a.stringOr("missing", "d")).isEqualTo("d");
        assertThat(a.stringOr("nothing", "d")).isEqualTo("d");
        assertThat(a.intOr("limit", 1)).isEqualTo(5);
        assertThat(a.intOr("whole", 1)).isEqualTo(3);
        assertThat(a.intOr("missing", 7)).isEqualTo(7);
        assertThat(a.boolOr("flag", false)).isTrue();
        assertThat(a.boolOr("missing", false)).isFalse();
    }

    @Test
    void failures_name_the_field() {
        Args a = Args.of(node);
        assertThatThrownBy(() -> a.require("path", "content")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("content");
        assertThatThrownBy(() -> a.string("limit")).hasMessageContaining("limit");
        assertThatThrownBy(() -> a.intOr("bad", 1)).hasMessageContaining("bad");
        assertThatThrownBy(() -> a.boolOr("bad", true)).hasMessageContaining("bad");
        assertThatThrownBy(() -> Args.of(Json.mapper().createArrayNode())).isInstanceOf(IllegalArgumentException.class);
    }
}
