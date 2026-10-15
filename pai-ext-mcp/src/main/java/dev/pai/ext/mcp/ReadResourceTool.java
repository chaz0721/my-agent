/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.Args;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;
import dev.pai.model.ToolDefinition;

/**
 * {@code mcp__<server>__read_resource}: reads one resource of a server that offers resources. The description lists the
 * first resources the server reported so the model knows what exists.
 */
final class ReadResourceTool implements Tool {

    /** Tool name suffix. */
    static final String SUFFIX = "read_resource";
    /** Resources listed in the description. */
    static final int LISTED = 20;

    private final ToolDefinition definition;
    private final McpClient client;

    ReadResourceTool(String server, List<McpResource> resources, McpClient client) {
        StringBuilder d = new StringBuilder("Read a resource of MCP server " + server + " by URI.");
        resources.stream().limit(LISTED)
            .forEach(r -> d.append("\n- ").append(r.uri()).append(r.name().isEmpty() ? "" : " (" + r.name() + ")"));
        if (resources.size() > LISTED) {
            d.append("\n- ... ").append(resources.size() - LISTED).append(" more");
        }
        JsonNode schema = JsonSchema.object().string("uri", "Resource URI").required("uri").build();
        this.definition = new ToolDefinition(McpTool.name(server, SUFFIX), d.toString(), schema);
        this.client = client;
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolEffectEnum effect() {
        return ToolEffectEnum.UNKNOWN;
    }

    @Override
    public boolean concurrencySafe() {
        return false;
    }

    @Override
    public boolean concurrencySafe(JsonNode input) {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) {
        ReadResourceResult r = client.readResource(Args.of(arguments).string("uri"));
        List<ContentBlock> blocks = new ArrayList<>();
        r.contents().forEach(c -> blocks.add(McpTool.resource(c)));
        if (blocks.isEmpty()) {
            return ToolResult.error("resource has no contents");
        }
        return new ToolResult(blocks, false, Optional.empty());
    }
}
