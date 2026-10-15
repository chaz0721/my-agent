/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.ContentBlock;

/**
 * The {@code bash} commands plan mode lets through: {@code ls}, {@code cat}, {@code head}, {@code tail}, {@code grep},
 * {@code rg}, {@code find}, {@code git status|log|diff|show}, {@code wc}, {@code tree}, {@code dir} and {@code type},
 * optionally joined by pipes. Anything else, any redirection, command list, substitution, quoting or escaping (so that
 * an option cannot be hidden from the checks), and any option that writes or runs a program ({@code find -exec},
 * {@code rg --pre}, {@code --output}, {@code tree -o}) is refused; on Windows, where the shell may be {@code cmd}, also
 * {@code %} and {@code ^}. A command that passes may still name a path outside the working directory
 * ({@link #mayLeaveCwd}); plan mode asks before running such a command.
 */
final class ReadOnlyCommands {

    /** Name of the built-in shell tool. */
    static final String BASH = "bash";

    private static final Pattern SEGMENT =
        Pattern.compile("\\s*(ls|cat|head|tail|grep|rg|find|wc|tree|dir|type|git\\s+(status|log|diff|show))(\\s[^|]*)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHELL_SYNTAX = Pattern.compile("[;&<>`$\\n\\r(){}'\"\\\\]|\\|\\|");
    private static final Pattern CMD_SYNTAX = Pattern.compile("[%^]");
    private static final List<Pattern> WRITING_OPTIONS =
        List.of(Pattern.compile("(^|\\s)-(exec|execdir|ok|okdir|delete|fprint|fprint0|fprintf|fls)\\b"),
            Pattern.compile("(^|\\s)--(pre|output|ext-diff|textconv|hostname-bin)\\b"),
            Pattern.compile("^\\s*tree\\b.*\\s-[A-Za-z]*[oR]"));
    private static final Pattern OUTSIDE = Pattern.compile("(^|\\s)/|~|\\.\\.|\\b[A-Za-z]:|%");
    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private ReadOnlyCommands() {}

    /**
     * Tells whether a call is a {@code bash} call whose command only reads.
     *
     * @param call the call
     * @return true for a whitelisted read-only command
     */
    static boolean isReadOnlyBash(ContentBlock.ToolCall call) {
        JsonNode command = call.arguments().path(CommandGuard.COMMAND_ARGUMENT);
        return BASH.equals(call.name()) && command.isTextual() && readOnly(command.asText(), WINDOWS);
    }

    /**
     * Tells whether a read-only command may read outside the working directory: an absolute path, {@code ~},
     * {@code ..}, a drive letter or a {@code %} variable appears in it.
     *
     * @param call a call that passed {@link #isReadOnlyBash}
     * @return true when the user should be asked
     */
    static boolean mayLeaveCwd(ContentBlock.ToolCall call) {
        return OUTSIDE.matcher(call.arguments().path(CommandGuard.COMMAND_ARGUMENT).asText()).find();
    }

    static boolean readOnly(String command, boolean cmdShell) {
        if (command.isBlank() || SHELL_SYNTAX.matcher(command).find()
            || cmdShell && CMD_SYNTAX.matcher(command).find()) {
            return false;
        }
        for (String segment : command.split("\\|", -1)) {
            if (!SEGMENT.matcher(segment).matches()
                || WRITING_OPTIONS.stream().anyMatch(p -> p.matcher(segment).find())) {
                return false;
            }
        }
        return true;
    }
}
