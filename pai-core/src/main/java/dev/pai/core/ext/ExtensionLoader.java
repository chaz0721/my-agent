/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Discovers and installs extensions.
 */
public final class ExtensionLoader {

    /**
     * Loads every {@link Extension} declared in {@code META-INF/services} on the class path.
     *
     * @return extensions in discovery order
     */
    public List<Extension> loadFromServiceLoader() {
        List<Extension> out = new ArrayList<>();
        ServiceLoader.load(Extension.class).forEach(out::add);
        return out;
    }

    /**
     * Installs extensions in order: each gets its own context and {@link Extension#onLoad} is called.
     *
     * @param extensions the extensions
     * @param paiHome base directory ({@code ~/.pai}); config dirs are {@code paiHome/ext/<id>}
     * @return the assembled host
     */
    public ExtensionHost install(List<Extension> extensions, Path paiHome) {
        ExtensionHost host = new ExtensionHost(paiHome);
        for (Extension e : extensions) {
            host.load(e);
        }
        return host;
    }
}
