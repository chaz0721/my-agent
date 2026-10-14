/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The shared Jackson mapper (java.time and Optional support, ISO-8601 instants, empty optionals omitted).
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new Jdk8Module())
        .addModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .serializationInclusion(JsonInclude.Include.NON_ABSENT).build();

    private Json() {}

    /**
     * Returns the shared, thread-safe mapper. Do not reconfigure it.
     *
     * @return the mapper
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Parses text that must be a JSON object; blank text is the empty object.
     *
     * @param text JSON text
     * @return the object, or empty when the text is not a JSON object
     */
    public static Optional<ObjectNode> parseObject(String text) {
        if (text.isBlank()) {
            return Optional.of(MAPPER.createObjectNode());
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            return node instanceof ObjectNode obj ? Optional.of(obj) : Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
