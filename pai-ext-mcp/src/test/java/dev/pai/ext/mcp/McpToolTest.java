/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolTest {

    private static JsonNode json(String s) throws Exception {
        return Json.mapper().readTree(s);
    }

    @Test
    void names_are_prefixed_sanitized_and_bounded() {
        assertThat(McpTool.name("fs", "read_file")).isEqualTo("mcp__fs__read_file");
        assertThat(McpTool.name("my server", "a.b/c")).isEqualTo("mcp__my_server__a_b_c");
        assertThat(McpTool.name("s", "x".repeat(100))).hasSize(McpTool.MAX_NAME);
    }

    @Test
    void schema_is_passed_on_unchanged_and_effect_is_unknown() throws Exception {
        JsonNode schema = json("{\"type\":\"object\",\"properties\":{\"p\":{\"anyOf\":[{\"type\":\"string\"},"
            + "{\"type\":\"null\"}]}},\"additionalProperties\":false,\"$defs\":{}}");
        McpClient unused = new McpClient(new InMemoryTransportImpl(), Duration.ofSeconds(1));
        McpTool t = new McpTool("s", new McpToolDescriptor("t", "d", schema), unused);
        assertThat(t.definition().parametersSchema()).isSameAs(schema);
        assertThat(t.effect()).isEqualTo(ToolEffectEnum.UNKNOWN);
        assertThat(t.concurrencySafe()).isFalse();
        assertThat(McpTool.schema(MissingNode.getInstance()).path("type").asText()).isEqualTo("object");
    }

    @Test
    void content_items_map_to_blocks() throws Exception {
        assertThat(McpTool.block(json("{\"type\":\"text\",\"text\":\"hi\"}"))).isEqualTo(new ContentBlock.Text("hi"));
        assertThat(McpTool.block(json("{\"type\":\"image\",\"data\":\"AAA\",\"mimeType\":\"image/jpeg\"}")))
            .isEqualTo(new ContentBlock.Image("AAA", "image/jpeg"));
        assertThat(McpTool.block(json("{\"type\":\"resource\",\"resource\":{\"uri\":\"f://a\",\"text\":\"body\"}}")))
            .isEqualTo(new ContentBlock.Text("body"));
        assertThat(McpTool.block(json("{\"type\":\"resource_link\",\"uri\":\"f://b\",\"name\":\"b\"}")))
            .isEqualTo(new ContentBlock.Text("[resource f://b b]"));
        assertThat(McpTool.block(json("{\"type\":\"audio\",\"data\":\"x\"}")).toString()).contains("audio");
        assertThat(McpTool.resource(json("{\"uri\":\"f://c\",\"mimeType\":\"application/pdf\",\"blob\":\"QUJD\"}")))
            .isEqualTo(new ContentBlock.Text("[binary resource f://c application/pdf, 4 base64 chars]"));
    }

    @Test
    void read_resource_tool_lists_known_resources() {
        McpClient unused = new McpClient(new InMemoryTransportImpl(), Duration.ofSeconds(1));
        List<McpResource> rs =
            IntStream.range(0, 25).mapToObj(i -> new McpResource("mem://" + i, "r" + i, "", "")).toList();
        ReadResourceTool t = new ReadResourceTool("s", rs, unused);
        assertThat(t.definition().name()).isEqualTo("mcp__s__read_resource");
        assertThat(t.definition().description()).contains("mem://0 (r0)", "mem://19", "5 more")
            .doesNotContain("mem://20");
    }
}
