/* pai-next — Apache-2.0 */
package dev.pai.core.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** Resolves a tool's path argument to the real file it names (links followed), kept only inside a directory. */
public final class RealPaths {

    private RealPaths() {}

    /**
     * Resolves {@code raw} against {@code cwd} and normalizes it, as the built-in tools do, then the longest existing
     * prefix (looked up without following links) becomes its real path and the rest is appended, so a file not created
     * yet resolves too.
     *
     * @param cwd the directory the path must stay in
     * @param raw the path as given, relative or absolute
     * @return the real path when inside the real {@code cwd}; empty when outside, invalid or a prefix cannot be
     *         resolved (dangling link, I/O error)
     */
    public static Optional<Path> inside(Path cwd, String raw) {
        try {
            Path base = cwd.toAbsolutePath().normalize();
            Optional<Path> root = real(base);
            return real(base.resolve(raw).normalize()).filter(t -> root.isPresent() && t.startsWith(root.get()));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> real(Path absolute) {
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        try {
            return Optional.of(
                existing == null ? absolute : existing.toRealPath().resolve(existing.relativize(absolute)).normalize());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
