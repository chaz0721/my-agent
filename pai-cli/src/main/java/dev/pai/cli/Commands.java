/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import dev.pai.core.ext.CommandContext;
import dev.pai.core.ext.CommandResult;
import dev.pai.core.ext.SlashCommand;
import dev.pai.core.prompt.ProjectContext;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionTree;
import dev.pai.model.ModelRef;
import dev.pai.tui.SessionTreeView;

/**
 * The slash commands built into the CLI. Each one is a thin wrapper over a {@link Repl} operation.
 */
final class Commands {

    static final String INIT_PROMPT = String.join("\n",
        "Create the project memory file PAI.md in the working directory. First look at the repository (README, build",
        "files, top-level directories, how tests run). Then write at most 60 lines covering: build and test commands,",
        "the layout of the code, conventions, pitfalls and things never to do. Be specific to this repository; no",
        "generic advice. Do not overwrite PAI.md if it exists.");

    private static final CommandResult NONE = new CommandResult("", false);

    private Commands() {}

    static List<SlashCommand> builtins(Repl repl) {
        return List.of(command("help", "List commands", (a, c) -> new CommandResult(help(repl), false)),
            command("quit", "Exit pai", (a, c) -> new CommandResult("", true)),
            command("model", "List models, or switch with /model <provider>/<model>", (a, c) -> model(repl, a)),
            command("tree", "Show the session tree", (a, c) -> new CommandResult(tree(repl), false)),
            command("fork", "Continue from an earlier entry: /fork <entryId>", (a, c) -> fork(repl, a)),
            command("label", "Label an entry: /label <entryId> <text>", (a, c) -> label(repl, a)),
            command("compact", "Summarize the conversation now to free context", (a, c) -> compact(repl)),
            command("clear", "Start a new session (the current one is kept on disk)", (a, c) -> {
                repl.clear();
                return new CommandResult("new session " + repl.session().value(), false);
            }), command("init", "Ask the model to write PAI.md for this project", (a, c) -> init(repl, a, c)),
            command("memory", "Show which project memory files are loaded", (a, c) -> memory(repl)));
    }

    private static String help(Repl repl) {
        StringBuilder sb = new StringBuilder();
        for (SlashCommand cmd : repl.setup().host().commands()) {
            sb.append(String.format(Locale.ROOT, "/%-14s %s", cmd.name(), cmd.description())).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static CommandResult model(Repl repl, String args) {
        ModelRef now = repl.current().model();
        String nowRef = now.providerId() + "/" + now.modelId();
        if (args.isEmpty()) {
            List<String> lines = new ArrayList<>();
            lines.add("* " + nowRef + describe(now.contextWindow(), now.capabilities()));
            for (ModelCatalog.Entry e : repl.setup().catalog().entries()) {
                if (!e.ref().equals(nowRef)) {
                    lines.add("  " + e.ref() + describe(e.contextWindow(), e.capabilities()));
                }
            }
            if (repl.setup().catalog().entries().isEmpty()) {
                lines.add("(no ~/.pai/" + ModelCatalog.FILE_NAME + "; add models there to switch)");
            }
            return new CommandResult(String.join("\n", lines), false);
        }
        ModelCatalog.Entry e = repl.setup().catalog().find(args)
            .orElseThrow(() -> new IllegalArgumentException("not in " + ModelCatalog.FILE_NAME + ": " + args));
        repl.switchModel(ProviderSetup.fromEntry(e, repl.setup().env(), repl.setup().debugHttp()));
        return new CommandResult("model " + e.ref() + " from the next message on", false);
    }

    private static String describe(int window, Set<ModelRef.CapabilityEnum> caps) {
        String names =
            caps.stream().map(c -> c.name().toLowerCase(Locale.ROOT)).sorted().collect(Collectors.joining(","));
        return " (window " + window + (names.isEmpty() ? "" : ", " + names) + ")";
    }

    private static SessionTree sessionTree(Repl repl) {
        return SessionTree.of(repl.setup().store().readAll(repl.session()));
    }

    private static String tree(Repl repl) {
        return SessionTreeView.render(sessionTree(repl), repl.leaf());
    }

    private static String resolve(Repl repl, String idOrPrefix) {
        return sessionTree(repl).resolve(idOrPrefix)
            .orElseThrow(() -> new IllegalArgumentException("no unique entry " + idOrPrefix + " (see /tree)"));
    }

    private static CommandResult fork(Repl repl, String args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("usage: /fork <entryId>");
        }
        String id = resolve(repl, args);
        repl.fork(id);
        return new CommandResult("leaf is now " + id + "; the next message continues from there", false);
    }

    private static CommandResult label(Repl repl, String args) {
        int space = args.indexOf(' ');
        if (space < 0) {
            throw new IllegalArgumentException("usage: /label <entryId> <text>");
        }
        String target = resolve(repl, args.substring(0, space));
        repl.append(
            new SessionEntry.LabelEntry("", repl.leaf(), Instant.now(), target, args.substring(space + 1).strip()));
        return new CommandResult("labelled " + target, false);
    }

    private static CommandResult compact(Repl repl) {
        Repl.Compaction c = repl.compactNow();
        return new CommandResult(
            c.compacted() ? "compacted " + c.before() + " -> " + c.after() + " messages" : "nothing to compact", false);
    }

    private static CommandResult init(Repl repl, String args, CommandContext ctx) {
        Path file = repl.setup().cwd().resolve(ProjectContext.FILE_NAME);
        if (Files.exists(file)) {
            return new CommandResult(file + " already exists; edit it directly", false);
        }
        ctx.sendUserMessage(INIT_PROMPT + (args.isEmpty() ? "" : "\n\n" + args));
        return NONE;
    }

    private static CommandResult memory(Repl repl) {
        List<String> lines = new ArrayList<>();
        for (Path p : ProjectContext.candidates(repl.setup().paiHome(), repl.setup().cwd())) {
            lines.add((Files.isRegularFile(p) ? "loaded   " : "missing  ") + p);
        }
        lines.add("(read at startup; edit the files and restart pai to apply)");
        return new CommandResult(String.join("\n", lines), false);
    }

    static SlashCommand command(String name, String description,
        BiFunction<String, CommandContext, CommandResult> body) {
        return new SlashCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public CommandResult run(String args, CommandContext ctx) {
                return body.apply(args, ctx);
            }
        };
    }
}
