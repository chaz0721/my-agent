/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import dev.pai.core.compaction.CharsTokenEstimatorImpl;
import dev.pai.core.compaction.CompactingHooks;
import dev.pai.core.compaction.CompactionPolicy;
import dev.pai.core.compaction.ModelSummarizerImpl;
import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.loop.AgentConfig;
import dev.pai.core.prompt.ProjectContext;
import dev.pai.core.prompt.SystemPromptBuilder;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.core.skill.SkillRegistry;
import dev.pai.core.skill.SkillsExtension;
import dev.pai.tui.PlainRendererImpl;
import dev.pai.tui.Renderer;
import dev.pai.tui.RendererFactory;

/**
 * Entry point: loads extensions, picks the provider, opens or resumes a session and runs the REPL.
 */
public final class Main {

    private static final int EXIT_USAGE = 2;
    private static final int EXIT_INTERRUPTED = 130;

    private Main() {}

    /**
     * Process entry point.
     *
     * @param args command line
     */
    public static void main(String[] args) {
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
        System.exit(run(args, System.in, System.out, System.err, System.getenv(), true));
    }

    static int run(String[] args, InputStream in, PrintStream out, PrintStream err, Map<String, String> env,
        boolean interactive) {
        CliOptions opts;
        try {
            opts = CliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(CliOptions.USAGE);
            return EXIT_USAGE;
        }
        if (opts.help()) {
            out.println(CliOptions.USAGE);
            return 0;
        }
        Path cwd = Path.of(opts.cwd().orElse(System.getProperty("user.dir"))).toAbsolutePath().normalize();
        Path paiHome = Optional.ofNullable(env.get("PAI_HOME")).map(Path::of)
            .orElse(Path.of(System.getProperty("user.home"), ".pai"));
        EnvConfig cfg = EnvConfig.load(env, cwd, paiHome);
        boolean debugHttp = "1".equals(env.get("PAI_DEBUG_HTTP"));
        ProviderSetup.Selected selected;
        ModelCatalog catalog;
        try {
            catalog = ModelCatalog.load(paiHome.resolve(ModelCatalog.FILE_NAME));
            selected = ProviderSetup.withCatalogLimits(ProviderSetup.select(opts, cfg, debugHttp), catalog);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        }
        SessionStore store = new JsonlSessionStoreImpl(paiHome.resolve("sessions"));
        Optional<SessionId> resumed = resume(opts, store, cwd, err);
        if (opts.resume() && resumed.isEmpty()) {
            return EXIT_USAGE;
        }
        AtomicReference<SessionId> session = new AtomicReference<>(resumed.orElseGet(() -> store.create(cwd)));
        ExtensionLoader loader = new ExtensionLoader();
        List<Extension> extensions = new ArrayList<>(loader.loadFromServiceLoader());
        extensions.add(new SkillsExtension(
            new SkillRegistry(List.of(paiHome.resolve("skills"), cwd.resolve(".pai").resolve("skills")))));
        ExtensionHost host = loader.install(extensions, paiHome);
        AtomicReference<Repl> replRef = new AtomicReference<>();
        CompactingHooks compacting = new CompactingHooks(new CharsTokenEstimatorImpl(), CompactionPolicy.defaults(),
            new ModelSummarizerImpl(() -> replRef.get().current().provider()), store, session::get);
        AgentConfig config = AgentConfig.of(selected.model(), systemPrompt(cwd, paiHome));
        Renderer renderer = interactive && in == System.in && out == System.out ? RendererFactory.create()
            : new PlainRendererImpl(in, inputCharset(), out);
        Repl repl =
            new Repl(new Repl.Setup(host, store, session, config, compacting, catalog, cfg, debugHttp, cwd, paiHome),
                selected, renderer);
        replRef.set(repl);
        Commands.builtins(repl).forEach(host::registerCommand);
        renderer
            .println("model=" + selected.model().providerId() + "/" + selected.model().modelId() + " session="
                + session.get().value() + " cwd=" + cwd
                + (resumed.isPresent() ? " resumed="
                    + store.buildContext(session.get(), store.leafId(session.get()).orElse("")).size() + " messages"
                    : ""));
        if (ProviderSetup.configuredFromCwdEnv(opts, cfg)) {
            renderer.println("! provider config loaded from ./.env (base URL " + selected.model().baseUrl()
                + ") - only run pai in directories you trust");
        }
        host.dispatch(new SessionEvent.SessionStarted(session.get(), resumed.isPresent()));
        if (interactive) {
            renderer.onInterrupt(() -> {
                if (!repl.interrupt()) {
                    renderer.close();
                    System.exit(EXIT_INTERRUPTED);
                }
            });
        }
        try {
            repl.run();
        } catch (IOException e) {
            err.println("input error: " + e.getMessage());
            return 1;
        } finally {
            host.dispatch(new SessionEvent.SessionEnded(session.get()));
            host.unloadAll();
            renderer.close();
        }
        return 0;
    }

    private static String systemPrompt(Path cwd, Path paiHome) {
        SystemPromptBuilder b = new SystemPromptBuilder().base(SystemPromptBuilder.defaultBase()).section("Environment",
            "Working directory: " + cwd + "\nPlatform: " + System.getProperty("os.name") + "\nDate: "
                + LocalDate.now());
        ProjectContext.load(paiHome, cwd).ifPresent(text -> b.section(ProjectContext.SECTION, text));
        return b.build();
    }

    private static Charset inputCharset() {
        return System.console() != null ? System.console().charset() : Charset.defaultCharset();
    }

    private static Optional<SessionId> resume(CliOptions opts, SessionStore store, Path cwd, PrintStream err) {
        if (!opts.resume()) {
            return Optional.empty();
        }
        if (opts.resumeId().isPresent()) {
            SessionId id = new SessionId(opts.resumeId().get());
            try {
                store.header(id);
                return Optional.of(id);
            } catch (IllegalArgumentException e) {
                err.println("no such session: " + id.value());
                return Optional.empty();
            }
        }
        Optional<SessionId> latest = store.mostRecent(cwd);
        if (latest.isEmpty()) {
            err.println("no session to resume in " + cwd);
        }
        return latest;
    }
}
