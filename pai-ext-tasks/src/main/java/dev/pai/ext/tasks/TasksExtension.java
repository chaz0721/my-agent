/* pai-next — Apache-2.0 */
package dev.pai.ext.tasks;

import java.util.Optional;

import dev.pai.core.ext.CommandContext;
import dev.pai.core.ext.CommandResult;
import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.ext.SlashCommand;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.model.ChatRequest;

/**
 * The model's task list (Claude Code's TodoWrite): {@code todo_write} replaces the list, {@code todo_read} returns it,
 * every request carries a {@code <tasks>} section in the system prompt with the rule for using the list and, when it is
 * not empty, the list (at most {@value #MAX_SECTION} characters; the rule alone stays the same all session), and
 * {@code /todo} shows it to the user ({@code /todo clear} empties it). The list is stored as {@code tasks} session
 * entries, one per change.
 */
public final class TasksExtension implements Extension {

    /** Extension id. */
    public static final String ID = "tasks";
    /** Longest {@code <tasks>} section. */
    static final int MAX_SECTION = 1500;
    static final String OPEN = "<tasks>\n";
    static final String CLOSE = "\n</tasks>";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onLoad(ExtensionContext ctx) {
        ctx.registerTool(new TodoTools.WriteTool(ctx));
        ctx.registerTool(new TodoTools.ReadTool(ctx));
        ctx.addHooks(new Injection(ctx));
        ctx.registerCommand(new TodoCommand(ctx));
    }

    /** The {@code <tasks>} section for a list, within {@value #MAX_SECTION} characters; only the rule when empty. */
    static String section(TodoList list) {
        if (list.isEmpty()) {
            return OPEN + TodoTools.GUIDANCE + CLOSE;
        }
        String head = OPEN + TodoTools.GUIDANCE + "\nCurrent list:\n";
        return head + list.render(MAX_SECTION - head.length() - CLOSE.length()) + CLOSE;
    }

    /**
     * Adds the rule and the current list to every request.
     */
    private static final class Injection implements AgentHooks {
        private final ExtensionContext ctx;

        private Injection(ExtensionContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
            String system = request.systemPrompt().map(s -> s + "\n\n").orElse("") + section(TodoTools.current(ctx));
            return new ChatRequest(request.model(), Optional.of(system), request.messages(), request.tools(),
                request.options());
        }
    }

    /**
     * {@code /todo [clear]}.
     */
    private static final class TodoCommand implements SlashCommand {
        private final ExtensionContext ctx;

        private TodoCommand(ExtensionContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public String name() {
            return "todo";
        }

        @Override
        public String description() {
            return "Show the task list; /todo clear empties it";
        }

        @Override
        public CommandResult run(String args, CommandContext cc) {
            String a = args.strip();
            if ("clear".equals(a)) {
                ctx.appendEntry(TodoList.CUSTOM_TYPE, TodoList.EMPTY.toJson());
                return new CommandResult("todo: list cleared", false);
            }
            if (!a.isEmpty()) {
                return new CommandResult("usage: /todo [clear]", false);
            }
            return new CommandResult(TodoTools.summary(TodoTools.current(ctx)), false);
        }
    }
}
