/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.util.List;

import dev.pai.model.Message;
import dev.pai.model.ToolDefinition;

/** Estimates how many input tokens a request will use. */
public interface TokenEstimator {

    /**
     * Estimates the token count of a request.
     *
     * @param msgs the context
     * @param tools offered tool definitions
     * @param systemPrompt the system prompt
     * @return estimated tokens
     */
    int estimate(List<Message> msgs, List<ToolDefinition> tools, String systemPrompt);
}
