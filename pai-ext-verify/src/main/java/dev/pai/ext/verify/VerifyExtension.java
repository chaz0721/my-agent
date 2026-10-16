/* pai-next — Apache-2.0 */
package dev.pai.ext.verify;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.ext.CommandContext;
import dev.pai.core.ext.CommandResult;
import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.ext.SlashCommand;
import dev.pai.model.CancelToken;
import dev.pai.model.Json;

/**
 * Deterministic verification after writes: matching {@code write}/{@code edit} calls run a build check and its result
 * is fed back to the model ({@link VerifyHooks}). Rules come from {@code <cwd>/.pai/verify.json} when the project is
 * trusted (ignored with a notice otherwise), else from the built-in {@value #DEFAULT_RULES} chosen by the build file in
 * the working directory ({@code pom.xml}, {@code package.json} with a {@code typecheck} script, {@code Cargo.toml},
 * {@code go.mod}; for Python any of {@code pyproject.toml}, {@code setup.py}, {@code setup.cfg},
 * {@code requirements.txt}, checking only the written files with {@code compileall}). The active rules are announced
 * once, and the user is asked before the first automatic run of a session. {@code /verify} runs them now (Ctrl+C stops
 * it), {@code /verify off} / {@code /verify on} switch them for the session.
 */
public final class VerifyExtension implements Extension {

    /** Extension id. */
    public static final String ID = "verify";
    /** Class path resource with the built-in rules. */
    static final String DEFAULT_RULES = "default-rules.json";
    /** Project rule file, relative to the working directory. */
    static final String PROJECT_FILE = ".pai/verify.json";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onLoad(ExtensionContext ctx) {
        List<VerifyRule> rules = rules(ctx);
        VerifyRunner runner = new VerifyRunner(ctx::tool, ctx.cwd(), ctx::notify);
        VerifyHooks hooks = new VerifyHooks(
            new VerifyHooks.Parts(rules, runner, ctx.cwd(), new VerifyConsent(ctx.ui(), ctx::notify), ctx::notify));
        ctx.addHooks(hooks);
        ctx.registerCommand(new VerifyCommand(hooks));
        ctx.onSession(SessionEvent.SessionStarted.class, e -> hooks.sessionStarted());
        rules.forEach(r -> ctx.notify("verify: " + r.describe()));
    }

    /** The project rules when trusted and present, else the built-in ones for the build files found. */
    static List<VerifyRule> rules(ExtensionContext ctx) {
        Path project = ctx.cwd().resolve(PROJECT_FILE);
        if (Files.isRegularFile(project)) {
            if (!ctx.projectTrusted()) {
                ctx.notify("verify: ignoring ./" + PROJECT_FILE + " (project not trusted); using built-in rules");
            } else {
                try {
                    return VerifyRule.parseAll(Json.mapper().readTree(project.toFile()));
                } catch (IOException | IllegalArgumentException e) {
                    ctx.notify(
                        "verify: cannot use ./" + PROJECT_FILE + ": " + e.getMessage() + "; using built-in rules");
                }
            }
        }
        return builtin(ctx.cwd());
    }

    static List<VerifyRule> builtin(Path cwd) {
        List<VerifyRule> out = new ArrayList<>();
        for (JsonNode set : defaults()) {
            String script = set.path("requiresScript").asText("");
            List<String> markers = new ArrayList<>();
            set.path("markers").forEach(m -> markers.add(m.asText()));
            if (set.has("marker")) {
                markers.add(set.path("marker").asText());
            }
            boolean found = markers.stream().map(cwd::resolve)
                .anyMatch(m -> Files.isRegularFile(m) && (script.isEmpty() || hasScript(m, script)));
            if (found) {
                out.addAll(VerifyRule.parseAll(set.path("rules")));
            }
        }
        return List.copyOf(out);
    }

    /** Whether a {@code package.json} declares a script. */
    private static boolean hasScript(Path packageJson, String script) {
        try {
            return Json.mapper().readTree(packageJson.toFile()).path("scripts").has(script);
        } catch (IOException e) {
            return false;
        }
    }

    private static JsonNode defaults() {
        try (InputStream in =
            Objects.requireNonNull(VerifyExtension.class.getResourceAsStream(DEFAULT_RULES), DEFAULT_RULES)) {
            return Json.mapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + DEFAULT_RULES, e);
        }
    }

    /**
     * Cancellation of a command run on the REPL thread: the host interrupts that thread on Ctrl+C.
     *
     * @param thread the thread running the command
     */
    private record InterruptToken(Thread thread) implements CancelToken {
        @Override
        public boolean isCancelled() {
            return thread.isInterrupted();
        }

        @Override
        public void onCancel(Runnable r) {
            // polled through isCancelled; the bash tool reacts to the interrupt itself
        }
    }

    /**
     * {@code /verify [off|on]}.
     */
    private static final class VerifyCommand implements SlashCommand {
        private final VerifyHooks hooks;

        private VerifyCommand(VerifyHooks hooks) {
            this.hooks = hooks;
        }

        @Override
        public String name() {
            return "verify";
        }

        @Override
        public String description() {
            return "Run the verification commands now; /verify off|on switches automatic runs for this session (on asks "
                + "again)";
        }

        private CommandResult runNow() {
            if (hooks.rules().isEmpty()) {
                return new CommandResult("verify: no rules for this directory", false);
            }
            List<VerifyRunner.Outcome> outcomes = hooks.runAll(new InterruptToken(Thread.currentThread()));
            boolean passed =
                outcomes.size() == hooks.rules().size() && outcomes.stream().allMatch(VerifyRunner.Outcome::passed);
            if (passed) {
                hooks.buildAccepted();
            }
            return new CommandResult(
                outcomes.stream().map(VerifyRunner.Outcome::report).collect(Collectors.joining("\n")), false);
        }

        @Override
        public CommandResult run(String args, CommandContext ctx) {
            return switch (args.strip()) {
                case "off" -> {
                    hooks.enabled(false);
                    yield new CommandResult("verify: off for this session", false);
                }
                case "on" -> {
                    hooks.enabled(true);
                    yield new CommandResult(
                        hooks.reconfirm() ? "verify: on" : "verify: automatic runs stay off " + "(not confirmed)",
                        false);
                }
                case "" -> runNow();
                default -> new CommandResult("usage: /verify [off|on]", false);
            };
        }
    }
}
