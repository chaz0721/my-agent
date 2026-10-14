/* pai-next — Apache-2.0 */
package dev.pai.core.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Minimal argument validation against a tool schema: required fields present and declared types matched.
 */
public final class SchemaCheck {

    private SchemaCheck() {}

    /**
     * Validates arguments.
     *
     * @param schema the tool's parameters schema
     * @param args the arguments
     * @return a description of every problem, or empty when valid
     */
    public static Optional<String> validate(JsonNode schema, JsonNode args) {
        if (!args.isObject()) {
            return Optional.of("arguments must be a JSON object");
        }
        List<String> problems = new ArrayList<>();
        for (JsonNode r : schema.path("required")) {
            JsonNode v = args.get(r.asText());
            if (v == null || v.isNull()) {
                problems.add("missing required argument: " + r.asText());
            }
        }
        for (Map.Entry<String, JsonNode> p : schema.path("properties").properties()) {
            JsonNode v = args.get(p.getKey());
            String type = p.getValue().path("type").asText("");
            if (v != null && !v.isNull() && !type.isEmpty() && !matches(type, v)) {
                problems.add("argument " + p.getKey() + " must be of type " + type);
            }
        }
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", problems));
    }

    private static boolean matches(String type, JsonNode v) {
        return switch (type) {
            case "string" -> v.isTextual();
            case "integer" -> v.isIntegralNumber() || v.isNumber() && v.asDouble() == Math.rint(v.asDouble());
            case "number" -> v.isNumber();
            case "boolean" -> v.isBoolean();
            case "array" -> v.isArray();
            case "object" -> v.isObject();
            default -> true;
        };
    }
}
