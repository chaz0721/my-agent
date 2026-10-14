/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Input authored by the user: text and image blocks.
 *
 * @param content content blocks (Text or Image)
 * @param timestamp creation time
 */
public record UserMessage(List<ContentBlock> content, Instant timestamp) implements Message {

    /**
     * Copies the content and validates fields.
     */
    public UserMessage {
        content = List.copyOf(content);
        Objects.requireNonNull(timestamp, "timestamp");
    }

    /**
     * Creates a single-text user message stamped now.
     *
     * @param s the text
     * @return the message
     */
    public static UserMessage text(String s) {
        return new UserMessage(List.of(new ContentBlock.Text(s)), Instant.now());
    }
}
