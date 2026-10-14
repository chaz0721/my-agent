/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.util.Optional;

import dev.pai.core.session.SessionId;
import dev.pai.model.ModelRef;

/**
 * What a slash command can see and do.
 */
public interface CommandContext {

    /**
     * Returns the active session.
     *
     * @return the session, if any
     */
    Optional<SessionId> session();

    /**
     * Returns the current model.
     *
     * @return the model
     */
    ModelRef model();

    /**
     * Prints a line to the user.
     *
     * @param s the line
     */
    void println(String s);

    /**
     * Queues a user message that is sent to the model as soon as the command returns, as if the user had typed it.
     *
     * @param text the message text
     */
    void sendUserMessage(String text);
}
