/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers and installs extensions: from the application class path, and from {@code *.jar} files in extension
 * directories, each jar in its own {@link URLClassLoader} whose parent is the loader of pai-core. A jar must carry
 * {@value #DESCRIPTOR} with {@code id} and {@code requiresCoreApi=}{@value #CORE_API_VERSION}; jars that are
 * unreadable, undeclared, built for another core API or whose providers fail are rejected with a warning and show up in
 * {@link ExtensionHost#statuses()}. One loader instance collects what it found for one {@link #install}.
 */
public final class ExtensionLoader {

    /** Version of the extension SPI this core implements; jars declare the version they need. */
    public static final int CORE_API_VERSION = 1;
    /** Descriptor every extension jar carries. */
    public static final String DESCRIPTOR = "META-INF/pai-extension.properties";

    private static final Logger log = LoggerFactory.getLogger(ExtensionLoader.class);

    private final Map<Extension, String> sources = new IdentityHashMap<>();
    private final List<ExtensionStatus> rejected = new ArrayList<>();

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
     * Loads the extensions of every {@code *.jar} directly inside the given directories, in directory order and by file
     * name within a directory; missing directories are skipped.
     *
     * @param dirs extension directories, for example {@code ~/.pai/extensions} and {@code <cwd>/.pai/extensions}
     * @return the extensions of all accepted jars
     */
    public List<Extension> loadFromDirs(List<Path> dirs) {
        List<Extension> out = new ArrayList<>();
        for (Path dir : dirs) {
            for (Path jar : jars(dir)) {
                out.addAll(loadJar(jar));
            }
        }
        return out;
    }

    /**
     * Installs extensions in order: each gets its own context and {@link Extension#onLoad} is called. The working
     * directory is the JVM's.
     *
     * @param extensions the extensions
     * @param paiHome base directory ({@code ~/.pai}); config dirs are {@code paiHome/ext/<id>}
     * @return the assembled host
     */
    public ExtensionHost install(List<Extension> extensions, Path paiHome) {
        return install(extensions, paiHome, Path.of("").toAbsolutePath());
    }

    /**
     * Installs extensions in order, together with the jars this loader rejected, for a trusted directory and without a
     * user to ask.
     *
     * @param extensions the extensions
     * @param paiHome base directory ({@code ~/.pai}); config dirs are {@code paiHome/ext/<id>}
     * @param cwd working directory reported by {@link ExtensionContext#cwd()}
     * @return the assembled host
     */
    public ExtensionHost install(List<Extension> extensions, Path paiHome, Path cwd) {
        return install(extensions, ExtensionHost.Settings.of(paiHome, cwd));
    }

    /**
     * Installs extensions in order, together with the jars this loader rejected. Extensions see the given settings
     * through their {@link ExtensionContext}; hooks chain in the order of {@code extensions}.
     *
     * @param extensions the extensions, class path ones first, then those of extension directories
     * @param settings pai home, working directory, project trust, user interaction and notices
     * @return the assembled host
     */
    public ExtensionHost install(List<Extension> extensions, ExtensionHost.Settings settings) {
        ExtensionHost host = new ExtensionHost(settings);
        rejected.forEach(host::reject);
        for (Extension e : extensions) {
            host.load(e, sources.getOrDefault(e, ExtensionStatus.CLASSPATH));
        }
        return host;
    }

    private static List<Path> jars(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.jar")) {
            s.forEach(out::add);
        } catch (IOException e) {
            log.warn("cannot list extension directory {}", dir, e);
        }
        out.sort(null);
        return out;
    }

    private List<Extension> loadJar(Path jar) {
        String name = jar.getFileName().toString();
        Properties descriptor;
        try {
            descriptor = descriptor(jar);
        } catch (IOException e) {
            return reject(name, jar, "unreadable jar: " + e.getMessage());
        }
        String id = descriptor.getProperty("id", "").strip();
        if (id.isEmpty()) {
            return reject(name, jar, "missing " + DESCRIPTOR + " or its id");
        }
        String required = descriptor.getProperty("requiresCoreApi", "").strip();
        if (!String.valueOf(CORE_API_VERSION).equals(required)) {
            return reject(id, jar,
                "requires core API " + (required.isEmpty() ? "?" : required) + ", this core has " + CORE_API_VERSION);
        }
        URLClassLoader cl;
        try {
            cl = new URLClassLoader("pai-ext-" + id, new URL[] {jar.toUri().toURL()},
                ExtensionLoader.class.getClassLoader());
        } catch (MalformedURLException e) {
            return reject(id, jar, "bad path: " + e.getMessage());
        }
        return instantiate(id, jar, cl);
    }

    /** Instantiates the providers the jar itself declares; providers visible through the parent are not repeated. */
    private List<Extension> instantiate(String id, Path jar, URLClassLoader cl) {
        List<Extension> out = new ArrayList<>();
        try {
            for (ServiceLoader.Provider<Extension> p : ServiceLoader.load(Extension.class, cl).stream().toList()) {
                if (p.type().getClassLoader() == cl) {
                    Extension e = p.get();
                    sources.put(e, jar.toString());
                    out.add(e);
                }
            }
        } catch (ServiceConfigurationError | LinkageError e) {
            out.forEach(sources::remove);
            closeQuietly(cl);
            return reject(id, jar, "cannot instantiate: " + e.getMessage());
        }
        if (out.isEmpty()) {
            closeQuietly(cl);
            return reject(id, jar, "declares no " + Extension.class.getName() + " service");
        }
        return out;
    }

    private static Properties descriptor(Path jar) throws IOException {
        Properties p = new Properties();
        try (JarFile jf = new JarFile(jar.toFile())) {
            ZipEntry entry = jf.getEntry(DESCRIPTOR);
            if (entry != null) {
                try (InputStream in = jf.getInputStream(entry)) {
                    p.load(in);
                }
            }
        }
        return p;
    }

    private List<Extension> reject(String id, Path jar, String reason) {
        log.warn("extension jar {} rejected: {}", jar, reason);
        rejected.add(ExtensionStatus.disabled(id, jar.toString(), reason));
        return List.of();
    }

    private static void closeQuietly(URLClassLoader cl) {
        try {
            cl.close();
        } catch (IOException e) {
            log.debug("closing class loader {} failed", cl.getName(), e);
        }
    }
}
