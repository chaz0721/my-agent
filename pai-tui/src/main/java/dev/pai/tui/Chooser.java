/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.List;
import java.util.Optional;

/**
 * A renderer that can ask a question with fixed answers as a choice box instead of an input line.
 */
interface Chooser {

    /**
     * Asks with a choice box.
     *
     * @param prompt the question, control characters already made visible
     * @param choices the answers
     * @return the chosen answer, {@code ""} when cancelled; empty when this renderer cannot show a box now (the caller
     *         then asks on the input line)
     * @throws IllegalStateException when the terminal is already reading a line on another thread
     */
    Optional<String> choose(String prompt, List<String> choices);
}
