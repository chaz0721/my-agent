/* pai-next — Apache-2.0 */
package dev.pai.ext.verify;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    /** Characters kept from the start and from the end of long output. */
    static final int KEEP_EACH_END = 1000;
    /** Maven boilerplate that only wastes context. */
    private static final List<String> NOISE =
        List.of("[ERROR] -> [Help", "Re-run Maven", "[ERROR] To see the full " + "stack trace",
            "[ERROR] For more information about the errors", "[ERROR] [Help 1]");

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
        boolean truncated = r.details().map(d -> d.path("truncated").asBoolean(false)).orElse(false);
        return new Outcome(false, label + " failed (exit " + exit + "), output:\n" + excerpt(text(r), truncated));
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

    /**
     * Drops Maven boilerplate lines and keeps the first and the last {@value #KEEP_EACH_END} characters of long (or
     * truncated) output, where the first error and the summary are.
     */
    static String excerpt(String s, boolean truncated) {
        String kept = s.lines().filter(l -> NOISE.stream().noneMatch(l::contains)).collect(Collectors.joining("\n"));
        if (kept.length() <= 2 * KEEP_EACH_END && !truncated) {
            return kept;
        }
        if (kept.length() <= 2 * KEEP_EACH_END) {
            return kept + "\n[output was truncated]";
        }
        return kept.substring(0, KEEP_EACH_END) + "\n[... " + (kept.length() - 2 * KEEP_EACH_END)
            + " characters left out ...]\n" + kept.substring(kept.length() - KEEP_EACH_END);
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
