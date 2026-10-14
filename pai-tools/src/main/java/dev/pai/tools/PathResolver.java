/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.Json;

/**
 * Resolves tool paths against the working directory and describes them for hooks.
 */
final class PathResolver {

    private PathResolver() {}

    static Path resolve(Path cwd, String path) {
        return cwd.resolve(path).toAbsolutePath().normalize();
    }

    static ObjectNode details(Path absolute) {
        return Json.mapper().createObjectNode().put("path", absolute.toString());
    }
}
