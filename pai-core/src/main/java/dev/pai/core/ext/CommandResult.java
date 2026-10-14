/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.util.Objects;

/**
 * Outcome of a slash command.
 *
 * @param output text to print, may be empty
 * @param quit whether the REPL should exit
 */
public record CommandResult(String output, boolean quit) {

    /**
     * Validates fields.
     */
    public CommandResult {
        Objects.requireNonNull(output, "output");
    }
}
