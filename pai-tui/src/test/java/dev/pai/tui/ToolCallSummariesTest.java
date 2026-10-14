/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallSummariesTest {

    private static ContentBlock.ToolCall call(String name, String key, String value) {
        return new ContentBlock.ToolCall("id", name, Json.mapper().createObjectNode().put(key, value));
    }

    @Test
    void builtin_tools_have_specific_lines() {
        assertThat(ToolCallSummaries.summarize(Events.READ, ToolResult.text("1\ta\n2\tb")))
            .isEqualTo("read src/Main.java (2 lines)");
        ToolResult wrote =
            ToolResult.text("wrote 5 bytes").withDetails(Json.mapper().createObjectNode().put("bytes", 5));
        assertThat(ToolCallSummaries.summarize(call("write", "path", "a.txt"), wrote))
            .isEqualTo("write a.txt (5 bytes)");
        ToolResult ran = new ToolResult(List.of(new ContentBlock.Text("x")), false,
            Optional.of(Json.mapper().createObjectNode().put("exitCode", 2)));
        assertThat(ToolCallSummaries.summarize(call("bash", "command", "ls -la\necho hi"), ran))
            .isEqualTo("$ ls -la ... (exit 2)");
        assertThat(ToolCallSummaries.summarize(call("edit", "path", "b.txt"), ToolResult.text("edited")))
            .isEqualTo("edit b.txt");
    }

    @Test
    void errors_unknown_tools_and_longCallarguments() {
        assertThat(ToolCallSummaries.summarize(Events.READ, ToolResult.error("file not found: src/Main.java\nmore")))
            .isEqualTo("read src/Main.java (file not found: src/Main.java ...)");
        String d = ToolCallSummaries.describe(call("mcp_search", "query", "q".repeat(200)));
        assertThat(d).startsWith("mcp_search {\"query\":\"qqq").endsWith("...");
        assertThat(d.length()).isLessThanOrEqualTo(60 + "mcp_search".length() + 1);
        assertThat(ToolCallSummaries.outcome(call("mcp_search", "q", "x"), ToolResult.text("ok"))).isEmpty();
    }

    @Test
    void mcp_tools_use_the_generic_call_form() {
        assertThat(ToolCallSummaries.describe(call("mcp__fs__read_file", "path", "a.txt")))
            .isEqualTo("mcp__fs__read_file({\"path\":\"a.txt\"})");
        String longCall = ToolCallSummaries.describe(call("mcp__fs__search", "q", "x".repeat(200)));
        assertThat(longCall).startsWith("mcp__fs__search({\"q\":\"xxx").endsWith("...)");
        assertThat(ToolCallSummaries.summarize(call("mcp__fs__x", "a", "b"), ToolResult.error("denied")))
            .isEqualTo("mcp__fs__x({\"a\":\"b\"}) (denied)");
    }
}
