/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import dev.pai.core.ext.CommandContext;
import dev.pai.core.ext.CommandResult;
import dev.pai.core.ext.SlashCommand;
import dev.pai.core.ext.UserInteraction;

/**
 * {@code /memory list | search <query> | delete <id> | clear | inject on|off}.
 */
final class MemoryCommand implements SlashCommand {

    static final String USAGE = "usage: /memory list | search <query> | delete <id> | clear | inject on|off";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final int SEARCH_LIMIT = 10;

    private final MemoryStore store;
    private final MemoryInjection injection;
    private final Optional<UserInteraction> ui;

    MemoryCommand(MemoryStore store, MemoryInjection injection, Optional<UserInteraction> ui) {
        this.store = store;
        this.injection = injection;
        this.ui = ui;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "Remembered facts: /memory list | search <q> | delete <id> | clear | inject on|off";
    }

    @Override
    public CommandResult run(String args, CommandContext ctx) {
        String[] parts = args.strip().split("\\s+", 2);
        String rest = parts.length > 1 ? parts[1].strip() : "";
        String out = switch (parts[0]) {
            case "", "list" -> lines(store.list(), "no memories");
            case "search" -> rest.isEmpty() ? USAGE
                : lines(Recall.rank(store.list(), rest, SEARCH_LIMIT).stream().map(Recall.Hit::entry).toList(),
                    "no matching memories");
            case "delete" ->
                rest.isEmpty() ? USAGE : store.delete(rest) ? "deleted " + rest : "no memory with id " + rest;
            case "clear" -> clear();
            case "inject" -> inject(rest);
            default -> USAGE;
        };
        return new CommandResult(out, false);
    }

    private String clear() {
        int n = store.list().size();
        if (n == 0) {
            return "no memories";
        }
        if (ui.isPresent() && !ui.get().confirm("Delete all " + n + " memories (global and this project)?")) {
            return "kept";
        }
        return "deleted " + store.clear() + " memories";
    }

    private String inject(String arg) {
        if ("on".equals(arg) || "off".equals(arg)) {
            injection.enabled("on".equals(arg));
        } else if (!arg.isEmpty()) {
            return USAGE;
        }
        return "memory injection " + (injection.enabled() ? "on" : "off");
    }

    private static String lines(List<MemoryEntry> entries, String empty) {
        if (entries.isEmpty()) {
            return empty;
        }
        StringBuilder sb = new StringBuilder();
        entries.forEach(e -> sb.append(line(e)).append('\n'));
        return sb.toString().stripTrailing();
    }

    static String line(MemoryEntry e) {
        String tags = e.tags().isEmpty() ? "" : "  #" + String.join(" #", e.tags());
        return "[" + e.id() + "] " + DAY.format(e.createdAt()) + " " + e.scope() + "  " + e.text() + tags;
    }
}
