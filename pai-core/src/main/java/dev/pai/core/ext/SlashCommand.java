/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

/**
 * A local command typed as {@code /name args}; never sent to the model.
 */
public interface SlashCommand {

    /**
     * Returns the name without the slash.
     *
     * @return the name
     */
    String name();

    /**
     * Returns a one-line description.
     *
     * @return the description
     */
    String description();

    /**
     * Runs the command.
     *
     * @param args text after the name, trimmed
     * @param ctx command environment
     * @return output and whether to quit
     */
    CommandResult run(String args, CommandContext ctx);
}
