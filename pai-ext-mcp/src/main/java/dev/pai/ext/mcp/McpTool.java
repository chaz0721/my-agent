/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.ToolDefinition;

/**
 * A server tool as a pai {@link Tool} named {@code mcp__<server>__<tool>}. The input schema is passed on exactly as the
 * server sent it; the effect is unknown, so calls never run in parallel. A tool-level failure ({@code isError}) becomes
 * an error result; protocol failures propagate as {@link McpException}.
 */
final class McpTool implements Tool {

    /** Prefix of every MCP tool name. */
    static final String PREFIX = "mcp__";
    /** Longest tool name providers accept. */
    static final int MAX_NAME = 64;
    /** Hash characters appended to names that had to be changed. */
    static final int HASH_CHARS = 6;

    private static final Pattern NOT_ALLOWED = Pattern.compile("[^A-Za-z0-9_-]");

    private final ToolDefinition definition;
    private final String remoteName;
    private final McpClient client;

    McpTool(String server, McpToolDescriptor d, McpClient client) {
        this.definition = new ToolDefinition(name(server, d.name()), d.description(), schema(d.inputSchema()));
        this.remoteName = d.name();
        this.client = client;
    }

    /**
     * Returns {@code mcp__<server>__<tool>}. When characters providers reject had to be replaced or the name had to be
     * cut to {@link #MAX_NAME}, the result ends in {@code _} plus {@value #HASH_CHARS} hex characters of a hash of the
     * original name, so distinct originals stay distinct.
     */
    static String name(String server, String tool) {
        String raw = PREFIX + server + "__" + tool;
        String clean =
            PREFIX + NOT_ALLOWED.matcher(server).replaceAll("_") + "__" + NOT_ALLOWED.matcher(tool).replaceAll("_");
        if (clean.equals(raw) && raw.length() <= MAX_NAME) {
            return raw;
        }
        int keep = MAX_NAME - HASH_CHARS - 1;
        return (clean.length() > keep ? clean.substring(0, keep) : clean) + "_" + hash(raw);
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, HASH_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** A missing or non-object schema becomes the empty object schema; any object schema is kept as is. */
    static JsonNode schema(JsonNode inputSchema) {
        if (inputSchema.isObject()) {
            return inputSchema;
        }
        ObjectNode empty = Json.mapper().createObjectNode().put("type", "object");
        empty.putObject("properties");
        return empty;
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
        CallToolResult r = client.callTool(remoteName, arguments, ctx.cancel());
        List<ContentBlock> blocks = new ArrayList<>();
        r.content().forEach(c -> blocks.add(block(c)));
        if (blocks.isEmpty()) {
            blocks.add(new ContentBlock.Text(r.structuredContent().map(JsonNode::toString).orElse("")));
        }
        ToolResult result = new ToolResult(blocks, r.isError(), Optional.empty());
        return r.structuredContent().map(result::withDetails).orElse(result);
    }

    /** Maps one MCP content item; kinds the model cannot take directly are described in text. */
    static ContentBlock block(JsonNode c) {
        String type = c.path("type").asText();
        return switch (type) {
            case "text" -> new ContentBlock.Text(c.path("text").asText());
            case "image" -> new ContentBlock.Image(c.path("data").asText(), c.path("mimeType").asText("image/png"));
            case "resource" -> resource(c.path("resource"));
            case "resource_link" -> new ContentBlock.Text("[resource " + c.path("uri").asText()
                + (c.path("name").asText("").isEmpty() ? "" : " " + c.path("name").asText()) + "]");
            default -> new ContentBlock.Text(c.toString());
        };
    }

    /** Maps an embedded resource or a {@code resources/read} content item. */
    static ContentBlock resource(JsonNode r) {
        if (r.has("text")) {
            return new ContentBlock.Text(r.get("text").asText());
        }
        String mime = r.path("mimeType").asText("");
        if (mime.startsWith("image/") && r.has("blob")) {
            return new ContentBlock.Image(r.get("blob").asText(), mime);
        }
        return new ContentBlock.Text("[binary resource " + r.path("uri").asText() + " " + mime + ", "
            + r.path("blob").asText().length() + " base64 chars]");
    }
}
