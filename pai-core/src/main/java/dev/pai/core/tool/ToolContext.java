/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import java.nio.file.Path;

import dev.pai.model.CancelToken;

/**
 * Environment of one tool execution.
 */
public interface ToolContext {

    /**
     * Returns the working directory relative paths resolve against.
     *
     * @return the cwd
     */
    Path cwd();

    /**
     * Returns the cancellation signal; long-running tools must check it.
     *
     * @return the token
     */
    CancelToken cancel();

    /**
     * Reports partial output; may be ignored by the consumer.
     *
     * @param partial progress text
     */
    void progress(String partial);
}
