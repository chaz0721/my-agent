/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Typed access to a tool's arguments object. Every failure is an {@link IllegalArgumentException} naming the field.
 */
public final class Args {

    private final JsonNode node;

    private Args(JsonNode node) {
        this.node = node;
    }

    /**
     * Wraps an arguments object.
     *
     * @param node the arguments
     * @return the accessor
     * @throws IllegalArgumentException when the node is not an object
     */
    public static Args of(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("arguments must be a JSON object");
        }
        return new Args(node);
    }

    /**
     * Requires the given fields to be present and non-null.
     *
     * @param keys field names
     * @return this
     */
    public Args require(String... keys) {
        for (String k : keys) {
            if (!present(k)) {
                throw new IllegalArgumentException("missing required argument: " + k);
            }
        }
        return this;
    }

    /**
     * Returns a required string field.
     *
     * @param k field name
     * @return the value
     */
    public String string(String k) {
        require(k);
        JsonNode v = node.get(k);
        if (!v.isTextual()) {
            throw new IllegalArgumentException("argument " + k + " must be a string");
        }
        return v.asText();
    }

    /**
     * Returns an optional string field.
     *
     * @param k field name
     * @param d default when absent
     * @return the value
     */
    public String stringOr(String k, String d) {
        return present(k) ? string(k) : d;
    }

    /**
     * Returns an optional integer field.
     *
     * @param k field name
     * @param d default when absent
     * @return the value
     */
    public int intOr(String k, int d) {
        if (!present(k)) {
            return d;
        }
        JsonNode v = node.get(k);
        if (!v.canConvertToInt() || !v.isIntegralNumber() && !isIntegralDouble(v)) {
            throw new IllegalArgumentException("argument " + k + " must be an integer");
        }
        return v.asInt();
    }

    /**
     * Returns an optional boolean field.
     *
     * @param k field name
     * @param d default when absent
     * @return the value
     */
    public boolean boolOr(String k, boolean d) {
        if (!present(k)) {
            return d;
        }
        JsonNode v = node.get(k);
        if (!v.isBoolean()) {
            throw new IllegalArgumentException("argument " + k + " must be a boolean");
        }
        return v.asBoolean();
    }

    private boolean present(String k) {
        return node.has(k) && !node.get(k).isNull();
    }

    private static boolean isIntegralDouble(JsonNode v) {
        return v.isNumber() && v.asDouble() == Math.rint(v.asDouble());
    }
}
