/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.core.internal.SchemaCheck;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaTest {

    private final ObjectNode schema = JsonSchema.object().string("path", "file path").integer("limit", "max lines")
        .bool("force", "overwrite").array("tags", "labels", "string").required("path").build();

    @Test
    void builds_object_schema() {
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").path("path").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("path").path("description").asText()).isEqualTo("file path");
        assertThat(schema.path("properties").path("limit").path("type").asText()).isEqualTo("integer");
        assertThat(schema.path("properties").path("force").path("type").asText()).isEqualTo("boolean");
        assertThat(schema.path("properties").path("tags").path("items").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("required").get(0).asText()).isEqualTo("path");
    }

    @Test
    void schema_check_reports_missing_and_mistyped_fields() {
        ObjectNode ok = Json.mapper().createObjectNode().put("path", "a").put("limit", 2);
        ok.putArray("tags").add("x");
        assertThat(SchemaCheck.validate(schema, ok)).isEmpty();
        ObjectNode bad = Json.mapper().createObjectNode().put("limit", "two").put("force", 1);
        assertThat(SchemaCheck.validate(schema, bad)).hasValueSatisfying(
            s -> assertThat(s).contains("missing required argument: path", "limit must be of type integer", "force"));
        assertThat(SchemaCheck.validate(schema, Json.mapper().createArrayNode())).isPresent();
    }

    @Test
    void registry_rejects_duplicates_and_tracks_source() {
        ToolRegistry r = new ToolRegistry();
        Tool t = new Tool() {
            @Override
            public dev.pai.model.ToolDefinition definition() {
                return new dev.pai.model.ToolDefinition("t", "d", schema);
            }

            @Override
            public ToolEffectEnum effect() {
                return ToolEffectEnum.UNKNOWN;
            }

            @Override
            public ToolResult execute(com.fasterxml.jackson.databind.JsonNode a, ToolContext c) {
                return ToolResult.text("");
            }
        };
        r.register(t, "ext-a");
        assertThat(t.concurrencySafe()).isFalse();
        assertThat(r.sourceOf("t")).contains("ext-a");
        assertThat(r.definitions()).extracting(dev.pai.model.ToolDefinition::name).isEqualTo(List.of("t"));
        assertThatThrownBy(() -> r.register(t, "ext-b")).isInstanceOf(IllegalStateException.class);
        assertThat(r.unregister("t")).isTrue();
        assertThat(r.all()).isEmpty();
    }
}
