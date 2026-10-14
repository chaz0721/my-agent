/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import dev.pai.model.Provider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads extensions from jars compiled at test time, so their classes are invisible to the test class path.
 */
class ExtensionLoaderTest {

    @TempDir
    Path tmp;

    private final List<URLClassLoader> opened = new ArrayList<>();

    /** Windows keeps open jars locked, so the temp directory can only be deleted once the loaders are closed. */
    @AfterEach
    void closeLoaders() throws IOException {
        for (URLClassLoader cl : opened) {
            cl.close();
        }
    }

    private List<Extension> load(ExtensionLoader loader, List<Path> dirs) {
        List<Extension> found = loader.loadFromDirs(dirs);
        for (Extension e : found) {
            if (e.getClass().getClassLoader() instanceof URLClassLoader cl) {
                opened.add(cl);
            }
        }
        return found;
    }

    private static String source(String id, boolean failOnLoad) {
        return String.join("\n", "package fixture;", "import dev.pai.core.ext.*;",
            "public class HelloExtension implements Extension {", "  public String id() { return \"" + id + "\"; }",
            "  public void onLoad(ExtensionContext ctx) {",
            failOnLoad ? "    if (true) { throw new IllegalStateException(\"boom\"); }" : "",
            "    String cwd = ctx.cwd().toString();", "    ctx.registerCommand(new SlashCommand() {",
            "      public String name() { return \"" + id + "\"; }",
            "      public String description() { return cwd; }",
            "      public CommandResult run(String a, CommandContext c) { return new CommandResult(\"hi\", false); }",
            "    });", "  }", "}");
    }

    /** Compiles {@code fixture.HelloExtension} and packs it with the given descriptor (null: none). */
    private Path jar(Path dir, String name, String id, String descriptor, boolean failOnLoad) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src-" + name).resolve("fixture"));
        Path classes = Files.createDirectories(tmp.resolve("classes-" + name));
        Path file = src.resolve("HelloExtension.java");
        Files.writeString(file, source(id, failOnLoad));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-cp", classPath(), "-d", classes.toString(), file.toString());
        assertThat(rc).as("javac").isZero();
        Files.createDirectories(dir);
        Path jar = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(os)) {
            put(out, "fixture/HelloExtension.class",
                Files.readAllBytes(classes.resolve("fixture").resolve("HelloExtension.class")));
            List<Path> inners;
            try (Stream<Path> files = Files.list(classes.resolve("fixture"))) {
                inners = files.toList();
            }
            for (Path inner : inners) {
                String n = inner.getFileName().toString();
                if (n.contains("$")) {
                    put(out, "fixture/" + n, Files.readAllBytes(inner));
                }
            }
            put(out, "META-INF/services/dev.pai.core.ext.Extension",
                "fixture.HelloExtension\n".getBytes(StandardCharsets.UTF_8));
            if (descriptor != null) {
                put(out, ExtensionLoader.DESCRIPTOR, descriptor.getBytes(StandardCharsets.UTF_8));
            }
        }
        return jar;
    }

    /** Surefire may hide the class path in a manifest jar, so the needed code sources are added explicitly. */
    private static String classPath() {
        StringBuilder sb = new StringBuilder(System.getProperty("java.class.path"));
        for (Class<?> c : List.of(Extension.class, Provider.class, Logger.class)) {
            try {
                sb.append(File.pathSeparator)
                    .append(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()));
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
        return sb.toString();
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    @Test
    void loads_a_jar_in_its_own_class_loader_and_reports_cwd() throws IOException {
        Path dir = tmp.resolve("ext");
        Path jar = jar(dir, "hello.jar", "hello", "id=hello\nrequiresCoreApi=1\n", false);
        ExtensionLoader loader = new ExtensionLoader();
        List<Extension> found = load(loader, List.of(dir, tmp.resolve("missing")));
        assertThat(found).hasSize(1);
        Extension e = found.get(0);
        assertThat(e.getClass().getClassLoader()).isInstanceOf(URLClassLoader.class)
            .isNotSameAs(ExtensionLoaderTest.class.getClassLoader());
        ExtensionHost host = loader.install(found, tmp.resolve("home"), tmp);
        assertThat(host.command("hello").orElseThrow().description()).isEqualTo(tmp.toString());
        assertThat(host.statuses()).containsExactly(ExtensionStatus.enabled("hello", jar.toString()));
    }

    @Test
    void broken_jar_is_rejected_without_failing_the_others() throws IOException {
        Path dir = tmp.resolve("ext");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a-broken.jar"), "not a zip");
        jar(dir, "b-hello.jar", "hello", "id=hello\nrequiresCoreApi=1\n", false);
        ExtensionLoader loader = new ExtensionLoader();
        List<Extension> found = load(loader, List.of(dir));
        ExtensionHost host = loader.install(found, tmp.resolve("home"), tmp);
        assertThat(found).extracting(Extension::id).containsExactly("hello");
        assertThat(host.statuses()).hasSize(2);
        ExtensionStatus broken = host.statuses().get(0);
        assertThat(broken.enabled()).isFalse();
        assertThat(broken.id()).isEqualTo("a-broken.jar");
        assertThat(broken.reason().orElseThrow()).startsWith("unreadable jar");
    }

    @Test
    void jar_for_another_core_api_is_rejected() throws IOException {
        Path dir = tmp.resolve("ext");
        jar(dir, "future.jar", "future", "id=future\nrequiresCoreApi=2\n", false);
        ExtensionLoader loader = new ExtensionLoader();
        assertThat(load(loader, List.of(dir))).isEmpty();
        ExtensionStatus s = loader.install(List.of(), tmp, tmp).statuses().get(0);
        assertThat(s.enabled()).isFalse();
        assertThat(s.reason().orElseThrow()).contains("requires core API 2");
    }

    @Test
    void jar_without_descriptor_is_rejected() throws IOException {
        Path dir = tmp.resolve("ext");
        jar(dir, "plain.jar", "plain", null, false);
        ExtensionLoader loader = new ExtensionLoader();
        assertThat(load(loader, List.of(dir))).isEmpty();
        assertThat(loader.install(List.of(), tmp, tmp).statuses().get(0).reason().orElseThrow())
            .contains(ExtensionLoader.DESCRIPTOR);
    }

    @Test
    void jar_whose_on_load_throws_is_disabled() throws IOException {
        Path dir = tmp.resolve("ext");
        jar(dir, "boom.jar", "boom", "id=boom\nrequiresCoreApi=1\n", true);
        ExtensionLoader loader = new ExtensionLoader();
        ExtensionHost host = loader.install(load(loader, List.of(dir)), tmp, tmp);
        assertThat(host.command("boom")).isEmpty();
        ExtensionStatus s = host.statuses().get(0);
        assertThat(s.enabled()).isFalse();
        assertThat(s.reason().orElseThrow()).contains("boom");
    }

    @Test
    void second_extension_with_the_same_id_is_skipped() throws IOException {
        Path home = tmp.resolve("home-ext");
        Path project = tmp.resolve("project-ext");
        jar(home, "hello.jar", "hello", "id=hello\nrequiresCoreApi=1\n", false);
        jar(project, "hello-copy.jar", "hello", "id=hello\nrequiresCoreApi=1\n", false);
        ExtensionLoader loader = new ExtensionLoader();
        List<Extension> found = load(loader, List.of(home, project));
        assertThat(found).hasSize(2);
        ExtensionHost host = loader.install(found, tmp, tmp);
        assertThat(host.statuses()).extracting(ExtensionStatus::enabled).containsExactly(true, false);
        assertThat(host.statuses().get(1).reason()).contains("duplicate id");
    }
}
