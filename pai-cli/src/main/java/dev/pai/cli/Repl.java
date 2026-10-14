/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import dev.pai.core.ext.CommandContext;
import dev.pai.core.ext.CommandResult;
import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.SlashCommand;
import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.AgentLoop;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.UserMessage;

/**
 * Line REPL: reads a line, runs slash commands locally, sends everything else to the loop and records every new message
 * in the session.
 */
final class Repl {

    private final AgentLoop loop;
    private final ExtensionHost host;
    private final SessionStore store;
    private final SessionId session;
    private final ModelRef model;
    private final PrintStream out;
    private final EventPrinter printer;
    private final List<Message> history;
    private final AtomicReference<CancelTokenSource> running = new AtomicReference<>();
    private Optional<String> leaf;

    Repl(Setup setup, List<Message> history, Optional<String> leaf, PrintStream out) {
        this.loop = setup.loop();
        this.host = setup.host();
        this.store = setup.store();
        this.session = setup.session();
        this.model = setup.model();
        this.out = out;
        this.printer = new EventPrinter(out);
        this.history = new ArrayList<>(history);
        this.leaf = leaf;
    }

    /**
     * What the REPL works with.
     *
     * @param loop the loop
     * @param host installed extensions
     * @param store session storage
     * @param session active session
     * @param model current model
     */
    record Setup(AgentLoop loop, ExtensionHost host, SessionStore store, SessionId session, ModelRef model) {
    }

    /** Cancels the running request; returns false when nothing was running (the caller should exit). */
    boolean interrupt() {
        CancelTokenSource c = running.get();
        if (c == null || c.isCancelled()) {
            return false;
        }
        c.cancel();
        return true;
    }

    void run(BufferedReader in) throws IOException {
        String line;
        while (true) {
            out.print("> ");
            out.flush();
            line = in.readLine();
            if (line == null) {
                return;
            }
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith("/")) {
                if (command(text)) {
                    return;
                }
                continue;
            }
            prompt(text);
        }
    }

    private boolean command(String text) {
        int space = text.indexOf(' ');
        String name = space < 0 ? text.substring(1) : text.substring(1, space);
        String args = space < 0 ? "" : text.substring(space + 1).strip();
        Optional<SlashCommand> cmd = host.command(name);
        if (cmd.isEmpty()) {
            out.println("unknown command: /" + name);
            return false;
        }
        CommandResult r = cmd.get().run(args, new CommandContext() {
            @Override
            public Optional<SessionId> session() {
                return Optional.of(session);
            }

            @Override
            public ModelRef model() {
                return model;
            }

            @Override
            public void println(String s) {
                out.println(s);
            }
        });
        if (!r.output().isEmpty()) {
            out.println(r.output());
        }
        return r.quit();
    }

    private void prompt(String text) {
        CancelTokenSource cancel = new CancelTokenSource();
        running.set(cancel);
        try {
            List<Message> added = loop.run(List.copyOf(history), UserMessage.text(text), this::onEvent, cancel);
            history.addAll(added);
        } finally {
            running.set(null);
        }
    }

    private void onEvent(AgentEvent event) {
        if (event instanceof AgentEvent.MessageEnd end) {
            leaf = Optional
                .of(store.append(session, new SessionEntry.MessageEntry("", leaf, Instant.now(), end.message())));
        }
        printer.accept(event);
        host.dispatch(event);
    }

    static List<SlashCommand> builtins(ModelRef model) {
        return List.of(command("quit", "Exit pai", (a, c) -> new CommandResult("", true)), command("model",
            "Show the current model", (a, c) -> new CommandResult(model.providerId() + "/" + model.modelId(), false)));
    }

    private static SlashCommand command(String name, String description,
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
