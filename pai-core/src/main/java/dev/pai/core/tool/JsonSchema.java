/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.Json;

/**
 * Small builder for object-shaped JSON Schemas of tool arguments.
 */
public final class JsonSchema {

    private final ObjectNode root = Json.mapper().createObjectNode();
    private final ObjectNode properties;
    private final ArrayNode required;

    private JsonSchema() {
        root.put("type", "object");
        properties = root.putObject("properties");
        required = root.putArray("required");
    }

    /**
     * Starts an object schema.
     *
     * @return the builder
     */
    public static JsonSchema object() {
        return new JsonSchema();
    }

    /**
     * Adds a string property.
     *
     * @param name property name
     * @param desc description
     * @return this
     */
    public JsonSchema string(String name, String desc) {
        return property(name, "string", desc);
    }

    /**
     * Adds an integer property.
     *
     * @param name property name
     * @param desc description
     * @return this
     */
    public JsonSchema integer(String name, String desc) {
        return property(name, "integer", desc);
    }

    /**
     * Adds a boolean property.
     *
     * @param name property name
     * @param desc description
     * @return this
     */
    public JsonSchema bool(String name, String desc) {
        return property(name, "boolean", desc);
    }

    /**
     * Adds an array property.
     *
     * @param name property name
     * @param desc description
     * @param itemType JSON Schema type of the items
     * @return this
     */
    public JsonSchema array(String name, String desc, String itemType) {
        property(name, "array", desc);
        ((ObjectNode)properties.get(name)).putObject("items").put("type", itemType);
        return this;
    }

    /**
     * Marks properties as required.
     *
     * @param names property names
     * @return this
     */
    public JsonSchema required(String... names) {
        for (String n : names) {
            required.add(n);
        }
        return this;
    }

    /**
     * Returns the schema; the builder must not be used afterwards.
     *
     * @return the schema object
     */
    public ObjectNode build() {
        return root.deepCopy();
    }

    private JsonSchema property(String name, String type, String desc) {
        properties.putObject(name).put("type", type).put("description", desc);
        return this;
    }
}
