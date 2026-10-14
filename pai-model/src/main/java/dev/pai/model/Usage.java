/* pai-next — Apache-2.0 */
package dev.pai.model;

/**
 * Token usage of one request. {@code input} excludes cached tokens.
 *
 * @param input uncached input tokens
 * @param output output tokens (including reasoning)
 * @param cacheRead input tokens served from the prompt cache
 * @param cacheWrite input tokens written to the prompt cache
 */
public record Usage(long input, long output, long cacheRead, long cacheWrite) {

    /** No usage. */
    public static final Usage ZERO = new Usage(0L, 0L, 0L, 0L);

    /**
     * Adds two usages field by field.
     *
     * @param o the other usage
     * @return the sum
     */
    public Usage plus(Usage o) {
        return new Usage(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite);
    }

    /**
     * Returns the sum of all four counters.
     *
     * @return total tokens
     */
    public long total() {
        return input + output + cacheRead + cacheWrite;
    }
}
