/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.CancelToken;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;

/**
 * Tool context over a temp directory.
 */
final class TestContext implements ToolContext {

    private final Path cwd;
    final CancelTokenSource cancel = new CancelTokenSource();
    final List<String> progress = new ArrayList<>();

    TestContext(Path cwd) {
        this.cwd = cwd;
    }

    @Override
    public Path cwd() {
        return cwd;
    }

    @Override
    public CancelToken cancel() {
        return cancel;
    }

    @Override
    public void progress(String partial) {
        progress.add(partial);
    }

    static ObjectNode args() {
        return Json.mapper().createObjectNode();
    }

    static String text(ToolResult r) {
        return ((ContentBlock.Text)r.content().get(0)).text();
    }
}
