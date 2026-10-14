/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether one extension is active, where it came from and, if it is not, why.
 *
 * @param id extension id (for a rejected jar, the declared id or the file name)
 * @param source {@link #CLASSPATH} or the jar path
 * @param enabled whether the extension is loaded
 * @param reason why it is disabled; empty when enabled
 */
public record ExtensionStatus(String id, String source, boolean enabled, Optional<String> reason) {

    /** Source of extensions found on the application class path. */
    public static final String CLASSPATH = "classpath";

    /**
     * Validates fields.
     */
    public ExtensionStatus {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates the status of a loaded extension.
     *
     * @param id extension id
     * @param source where it came from
     * @return the status
     */
    public static ExtensionStatus enabled(String id, String source) {
        return new ExtensionStatus(id, source, true, Optional.empty());
    }

    /**
     * Creates the status of a disabled or rejected extension.
     *
     * @param id extension id
     * @param source where it came from
     * @param reason why
     * @return the status
     */
    public static ExtensionStatus disabled(String id, String source, String reason) {
        return new ExtensionStatus(id, source, false, Optional.of(reason));
    }
}
