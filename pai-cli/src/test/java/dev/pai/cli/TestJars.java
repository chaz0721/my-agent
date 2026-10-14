/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionLoader;

/**
 * Builds a minimal extension jar at test time, so its class is not on the test class path.
 */
final class TestJars {

    private TestJars() {}

    /** Compiles {@code fixture.CliFixtureExtension} with the given id into {@code dir/<id>.jar}. */
    static Path extensionJar(Path work, Path dir, String id) throws IOException {
        Path src = Files.createDirectories(work.resolve("src").resolve("fixture"));
        Path classes = Files.createDirectories(work.resolve("classes"));
        Path file = src.resolve("CliFixtureExtension.java");
        Files.writeString(file, "package fixture;\npublic class CliFixtureExtension implements "
            + "dev.pai.core.ext.Extension {\n  public String id() { return \"" + id + "\"; }\n}\n");
        StringBuilder cp = new StringBuilder(System.getProperty("java.class.path"));
        try {
            cp.append(File.pathSeparator)
                .append(Path.of(Extension.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", cp.toString(), "-d",
            classes.toString(), file.toString());
        if (rc != 0) {
            throw new IllegalStateException("javac failed: " + rc);
        }
        Files.createDirectories(dir);
        Path jar = dir.resolve(id + ".jar");
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(os)) {
            List<Path> compiled;
            try (Stream<Path> s = Files.list(classes.resolve("fixture"))) {
                compiled = s.toList();
            }
            for (Path c : compiled) {
                put(out, "fixture/" + c.getFileName(), Files.readAllBytes(c));
            }
            put(out, "META-INF/services/" + Extension.class.getName(),
                "fixture.CliFixtureExtension\n".getBytes(StandardCharsets.UTF_8));
            put(out, ExtensionLoader.DESCRIPTOR,
                ("id=" + id + "\nrequiresCoreApi=" + ExtensionLoader.CORE_API_VERSION + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
