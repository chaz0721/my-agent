/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;

/**
 * One-line descriptions of tool calls and their outcomes. Tool-specific presentation lives here, keyed by tool name;
 * unknown tools get a generic form.
 */
public final class ToolCallSummaries {

    private static final int MAX_ARG_CHARS = 60;
    private static final int MAX_ERROR_CHARS = 80;
    /** Name prefix of tools served by MCP servers ({@code mcp__<server>__<tool>}). */
    private static final String MCP_PREFIX = "mcp__";

    private static final Map<String,
        Function<JsonNode, String>> DESCRIBE = Map.of("read", a -> "read " + a.path("path").asText(), "write",
            a -> "write " + a.path("path").asText(), "edit", a -> "edit " + a.path("path").asText(), "bash",
            a -> "$ " + firstLine(a.path("command").asText()), "load_skill",
            a -> "load_skill " + a.path("name").asText());

    private static final Map<String, BiFunction<JsonNode, ToolResult, Optional<String>>> OUTCOME =
        Map.of("read", (a, r) -> Optional.of(lines(text(r)) + " lines"), "write",
            (a, r) -> r.details().map(d -> d.path("bytes").asLong() + " bytes"), "bash",
            (a, r) -> r.details().map(d -> "exit " + d.path("exitCode").asInt()));

    private ToolCallSummaries() {}

    /**
     * Describes a call, for example {@code read src/Main.java}; MCP tools read {@code mcp__server__tool({"a":1})}.
     *
     * @param call the call
     * @return one line
     */
    public static String describe(ContentBlock.ToolCall call) {
        if (call.name().startsWith(MCP_PREFIX)) {
            String args = cut(call.arguments().toString(), MAX_ARG_CHARS);
            return call.name() + "(" + args + ")";
        }
        Function<JsonNode, String> f = DESCRIBE.get(call.name());
        String s = f != null ? f.apply(call.arguments()) : call.name() + " " + call.arguments();
        return cut(s, MAX_ARG_CHARS + call.name().length() + 1);
    }

    /**
     * Describes the outcome of a call: a short fact on success, the first error line on failure.
     *
     * @param call the call
     * @param r the result
     * @return the outcome, empty when there is nothing worth showing
     */
    public static Optional<String> outcome(ContentBlock.ToolCall call, ToolResult r) {
        if (r.isError()) {
            return Optional.of(cut(firstLine(text(r)), MAX_ERROR_CHARS));
        }
        BiFunction<JsonNode, ToolResult, Optional<String>> f = OUTCOME.get(call.name());
        return f != null ? f.apply(call.arguments(), r) : Optional.empty();
    }

    /**
     * Summarizes a finished call, for example {@code read src/Main.java (120 lines)}.
     *
     * @param call the call
     * @param r the result
     * @return one line
     */
    public static String summarize(ContentBlock.ToolCall call, ToolResult r) {
        return describe(call) + outcome(call, r).map(o -> " (" + o + ")").orElse("");
    }

    static String text(ToolResult r) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : r.content()) {
            if (b instanceof ContentBlock.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    private static long lines(String s) {
        return s.isEmpty() ? 0L : s.lines().count();
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s.strip() : s.substring(0, nl).strip() + " ...";
    }

    static String cut(String s, int max) {
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max - 3) + "...";
    }
}
