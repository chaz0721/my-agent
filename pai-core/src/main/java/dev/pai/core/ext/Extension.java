/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

/**
 * A unit that registers tools, commands, providers and hooks through {@link ExtensionContext} (ADR 0005). Discovered
 * with {@link java.util.ServiceLoader}.
 */
public interface Extension {

    /**
     * Returns a stable id, used for tool source tags and the config directory.
     *
     * @return the id
     */
    String id();

    /**
     * Called once when the extension is installed.
     *
     * @param ctx registration surface
     */
    default void onLoad(ExtensionContext ctx) {}

    /**
     * Called once on shutdown.
     */
    default void onUnload() {}
}
