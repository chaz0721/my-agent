/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.util.List;

/**
 * Asks the person at the terminal a question. Calls block until answered; implementations serialize concurrent calls.
 * Only offered when pai runs interactively (see {@link ExtensionContext#ui()}).
 */
public interface UserInteraction {

    /**
     * Shows a prompt and waits for one of the choices (compared ignoring case).
     *
     * @param prompt the question, possibly several lines
     * @param choices accepted answers, for example {@code y}, {@code n}, {@code a}
     * @return the chosen answer as given in {@code choices}, or an empty string when the user gave none (end of input
     *         or interrupt)
     */
    String ask(String prompt, List<String> choices);

    /**
     * Asks a yes/no question.
     *
     * @param prompt the question
     * @return true only for an explicit yes
     */
    boolean confirm(String prompt);
}
