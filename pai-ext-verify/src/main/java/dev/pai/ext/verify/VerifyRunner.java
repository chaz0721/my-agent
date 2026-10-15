/* pai-next — Apache-2.0 */
package dev.pai.ext.verify;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.CancelToken;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;

/**
 * Runs a rule's command through the built-in {@code bash} tool (same shell, timeout, process-tree kill and output limit
 * as the model's commands). The hooks chain is not involved: the commands come from the built-in rules or the trusted
 * project's {@code verify.json}, never from the model, so neither approval nor the command guard applies.
 */
final class VerifyRunner {

    /** Name of the built-in shell tool. */
    static final String BASH = "bash";
    /** Longest output tail fed back. */
    static final int MAX_TAIL = 2000;

    private final Function<String, Optional<Tool>> tools;
    private final Path cwd;

    /**
     * Outcome of one command.
     *
     * @param passed whether it exited with 0
     * @param report what is fed back, labelled {@code [verify: <command>]}
     */
    record Outcome(boolean passed, String report) {
    }

    VerifyRunner(Function<String, Optional<Tool>> tools, Path cwd) {
        this.tools = tools;
        this.cwd = cwd;
    }

    Outcome run(VerifyRule rule, CancelToken cancel) {
        String label = "[verify: " + rule.run() + "]";
        Optional<Tool> bash = tools.apply(BASH);
        if (bash.isEmpty()) {
            return new Outcome(false, label + " skipped: the bash tool is not available");
        }
        JsonNode args =
            Json.mapper().createObjectNode().put("command", rule.run()).put("timeoutSeconds", rule.timeoutSeconds());
        ToolResult r;
        try {
            r = bash.get().execute(args, new Context(cwd, cancel));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, label + " interrupted");
        } catch (Exception e) {
            return new Outcome(false, label + " could not run: " + e.getMessage());
        }
        int exit = r.details().map(d -> d.path("exitCode").asInt(-1)).orElse(-1);
        if (!r.isError() && exit == 0) {
            return new Outcome(true, label + " passed");
        }
        return new Outcome(false, label + " failed (exit " + exit + "), output tail:\n" + tail(text(r)));
    }

    private static String text(ToolResult r) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : r.content()) {
            if (b instanceof ContentBlock.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString().strip();
    }

    static String tail(String s) {
        return s.length() <= MAX_TAIL ? s : "..." + s.substring(s.length() - MAX_TAIL + 3);
    }

    /**
     * Tool context of a verification command.
     *
     * @param cwd working directory
     * @param cancel the run's cancellation
     */
    private record Context(Path cwd, CancelToken cancel) implements ToolContext {
        @Override
        public void progress(String partial) {
            // verification output is reported when the command ends
        }
    }
}
