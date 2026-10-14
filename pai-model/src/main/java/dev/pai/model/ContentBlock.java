/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One segment of a message: text, thinking, image or tool call.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = ContentBlock.Text.class, name = "text"),
    @JsonSubTypes.Type(value = ContentBlock.Thinking.class, name = "thinking"),
    @JsonSubTypes.Type(value = ContentBlock.Image.class, name = "image"),
    @JsonSubTypes.Type(value = ContentBlock.ToolCall.class, name = "tool_call")})
public sealed interface ContentBlock
    permits ContentBlock.Text, ContentBlock.Thinking, ContentBlock.Image, ContentBlock.ToolCall {

    /**
     * Plain text.
     *
     * @param text the text
     */
    record Text(String text) implements ContentBlock {

        /**
         * Validates the text.
         */
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Model reasoning. The signature is provider-opaque data the codec sends back on the next request.
     *
     * @param text reasoning text
     * @param signature provider-specific replay data
     */
    record Thinking(String text, Optional<String> signature) implements ContentBlock {

        /**
         * Validates fields and normalizes an absent signature.
         */
        public Thinking {
            Objects.requireNonNull(text, "text");
            signature = signature == null ? Optional.empty() : signature;
        }
    }

    /**
     * Base64 encoded image.
     *
     * @param base64 image bytes as base64
     * @param mimeType media type such as image/png
     */
    record Image(String base64, String mimeType) implements ContentBlock {

        /**
         * Validates fields.
         */
        public Image {
            Objects.requireNonNull(base64, "base64");
            Objects.requireNonNull(mimeType, "mimeType");
        }
    }

    /**
     * A request from the model to run a tool. Arguments are always a parsed JSON object.
     *
     * @param id provider-assigned call id
     * @param name tool name
     * @param arguments parsed arguments object
     */
    record ToolCall(String id, String name, JsonNode arguments) implements ContentBlock {

        /**
         * Validates fields.
         */
        public ToolCall {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(arguments, "arguments");
        }
    }
}
