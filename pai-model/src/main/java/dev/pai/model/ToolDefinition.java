/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the model sees of a tool: name, description and a JSON Schema for its arguments.
 *
 * @param name tool name
 * @param description what the tool does
 * @param parametersSchema JSON Schema of the arguments object
 */
public record ToolDefinition(String name, String description, JsonNode parametersSchema) {

    /**
     * Validates fields.
     */
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersSchema, "parametersSchema");
    }
}
