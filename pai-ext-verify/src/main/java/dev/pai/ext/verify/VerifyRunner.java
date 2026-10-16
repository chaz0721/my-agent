/* pai-next — Apache-2.0 */
package dev.pai.ext.verify;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
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
 * project's {@code verify.json}, never from the model, so neither approval nor the command guard applies. In a command
 * {@value #PATH_TOKEN} becomes the files the turn wrote (only plain relative paths, the model chose them; the others
 * are left out with a notice) or, for {@code /verify}, the tracked files matching the rule ({@code allFiles} outside
 * git), and {@code {python}} the first of {@code python}, {@code python3} that runs ({@code --version}, probed once);
 * when none does the rule is skipped with one notice.
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

    /** Placeholder for the changed files. */
    static final String PATH_TOKEN = "{path}";
    /** Placeholder for the JVM's temporary directory. */
    static final String TMP_TOKEN = "{tmp}";
    private static final Map<String, List<String>> INTERPRETERS = Map.of("{python}", List.of("python", "python3"));
    private static final Pattern PLAIN_PATH = Pattern.compile("[\\w.+@/-]+");
    private static final String GIT_LS_FILES = "git -c core.fsmonitor=false -c core.quotePath=true ls-files";

    private final Function<String, Optional<Tool>> tools;
    private final Path cwd;
    private final Consumer<String> notifier;
    private final Map<String, Optional<String>> interpreters = new HashMap<>();

    /**
     * Outcome of one command.
     *
     * @param passed whether it exited with 0
     * @param report what is fed back, labelled {@code [verify: <command>]}
     */
    record Outcome(boolean passed, String report) {
    }

    VerifyRunner(Function<String, Optional<Tool>> tools, Path cwd) {
        this(tools, cwd, n -> {
        });
    }

    VerifyRunner(Function<String, Optional<Tool>> tools, Path cwd, Consumer<String> notifier) {
        this.tools = tools;
        this.cwd = cwd;
        this.notifier = notifier;
    }

    /**
     * Runs a rule for {@code /verify}: in a git repository {@value #PATH_TOKEN} stands for the tracked files that match
     * the rule's glob, elsewhere for the rule's {@code allFiles}.
     */
    Outcome run(VerifyRule rule, CancelToken cancel) {
        return run(rule, Optional.empty(), cancel).orElseGet(
            () -> new Outcome(false, "[verify: " + rule.run() + "] skipped: no interpreter or no file to check"));
    }

    /**
     * Runs a rule after writes.
     *
     * @param rule the rule
     * @param paths the written files, relative and {@code /}-separated, for {@value #PATH_TOKEN}
     * @param cancel cancellation
     * @return the outcome, empty when the rule's interpreter is missing (the user was told once) or no written file is
     *         plain enough to put in the command (each such file is reported to the user, not to the model)
     */
    Optional<Outcome> run(VerifyRule rule, List<String> paths, CancelToken cancel) {
        return run(rule, Optional.of(paths), cancel);
    }

    private Optional<Outcome> run(VerifyRule rule, Optional<List<String>> written, CancelToken cancel) {
        Optional<Tool> bash = tools.apply(BASH);
        if (bash.isEmpty()) {
            return Optional
                .of(new Outcome(false, "[verify: " + rule.run() + "] skipped: the bash tool is not available"));
        }
        String command = rule.run();
        for (Map.Entry<String, List<String>> e : INTERPRETERS.entrySet()) {
            if (command.contains(e.getKey())) {
                Optional<String> exe = interpreter(bash.get(), e.getValue(), cancel);
                if (exe.isEmpty()) {
                    return Optional.empty();
                }
                command = command.replace(e.getKey(), exe.get());
            }
        }
        command = command.replace(TMP_TOKEN, tmpDir(System.getProperty("java.io.tmpdir", ".")));
        if (command.contains(PATH_TOKEN)) {
            Optional<String> args =
                written.isPresent() ? Optional.of(plain(written.get())) : tracked(bash.get(), rule, cancel);
            if (args.isPresent() && args.get().isEmpty()) {
                return Optional.empty();
            }
            command = command.replace(PATH_TOKEN, args.orElse(rule.allFiles()));
        }
        return Optional.of(execute(bash.get(), command, rule.timeoutSeconds(), cancel));
    }

    /**
     * The temporary directory as {@value #TMP_TOKEN} becomes: absolute, normalized, forward slashes, no trailing slash,
     * in double quotes when it holds anything but plain path characters. Expanded here, not by the shell, because the
     * {@code cmd} fallback does not expand {@code ${TMPDIR:-/tmp}}.
     */
    static String tmpDir(String dir) {
        String d = java.nio.file.Path.of(dir).toAbsolutePath().normalize().toString().replace('\\', '/');
        while (d.length() > 1 && d.endsWith("/") && !d.endsWith(":/")) {
            d = d.substring(0, d.length() - 1);
        }
        return d.matches("[\\w.:/@+-]+") ? d : "\"" + d + "\"";
    }

    /** The paths plain enough for a command line, joined; the others are reported to the user and left out. */
    private String plain(List<String> paths) {
        List<String> kept = new ArrayList<>();
        for (String p : paths) {
            if (PLAIN_PATH.matcher(p).matches() && !p.startsWith("-")) {
                kept.add(p);
            } else {
                notifier.accept("verify: not checking " + p + " (the name is not plain enough to put in a command)");
            }
        }
        return String.join(" ", kept);
    }

    /**
     * The tracked files matching the rule's glob, joined (empty when none), or empty when this is no git repository.
     * The file system monitor is switched off so that git runs no program the repository configures.
     */
    private Optional<String> tracked(Tool bash, VerifyRule rule, CancelToken cancel) {
        ToolResult r;
        try {
            r = bash.execute(Json.mapper().createObjectNode().put("command", GIT_LS_FILES).put("timeoutSeconds", 30),
                new Context(cwd, cancel));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
        if (r.isError() || r.details().map(d -> d.path("exitCode").asInt(-1)).orElse(-1) != 0) {
            return Optional.empty();
        }
        Pattern glob = VerifyRule.glob(rule.pathGlob());
        List<String> files = text(r).lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("[exit"))
            .filter(l -> glob.matcher(l).matches()).toList();
        return Optional.of(plain(files));
    }

    /** The first candidate whose {@code --version} exits with 0, probed once per placeholder and session. */
    private synchronized Optional<String> interpreter(Tool bash, List<String> candidates, CancelToken cancel) {
        String key = String.join(",", candidates);
        Optional<String> known = interpreters.get(key);
        if (known != null) {
            return known;
        }
        for (String c : candidates) {
            if (execute(bash, c + " --version", 10, cancel).passed()) {
                interpreters.put(key, Optional.of(c));
                return Optional.of(c);
            }
        }
        if (!cancel.isCancelled()) {
            interpreters.put(key, Optional.empty());
            notifier.accept("verify: none of " + String.join(", ", candidates) + " runs here; skipping the rules "
                + "that need it");
        }
        return Optional.empty();
    }

    private Outcome execute(Tool bash, String command, int timeoutSeconds, CancelToken cancel) {
        String label = "[verify: " + command + "]";
        JsonNode args = Json.mapper().createObjectNode().put("command", command).put("timeoutSeconds", timeoutSeconds);
        ToolResult r;
        try {
            r = bash.execute(args, new Context(cwd, cancel));
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
