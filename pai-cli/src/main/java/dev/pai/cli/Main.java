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
import dev.pai.model.ModelRef;
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
        System.exit(run(args, new Io(System.in, System.out, System.err, true), System.getenv()));
    }

    /**
     * Standard streams of a run.
     *
     * @param in input
     * @param out output
     * @param err diagnostics
     * @param interactive whether this is the real process (terminal UI and Ctrl+C handling)
     */
    record Io(InputStream in, PrintStream out, PrintStream err, boolean interactive) {
    }

    /**
     * Where pai runs and how it is configured.
     *
     * @param cwd working directory
     * @param paiHome the {@code ~/.pai} directory
     * @param cfg configuration layers
     * @param debugHttp whether request bodies are logged
     */
    private record Layout(Path cwd, Path paiHome, EnvConfig cfg, boolean debugHttp) {
    }

    /**
     * Everything resolved before the REPL is assembled.
     *
     * @param layout directories and configuration
     * @param store session storage
     * @param selected startup provider and model
     * @param catalog configured models
     * @param resumed the resumed session, if any
     */
    private record Startup(Layout layout, SessionStore store, ProviderSetup.Selected selected, ModelCatalog catalog,
        Optional<SessionId> resumed) {
    }

    static int run(String[] args, Io io, Map<String, String> env) {
        CliOptions opts;
        try {
            opts = CliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            io.err().println(e.getMessage());
            io.err().println(CliOptions.USAGE);
            return EXIT_USAGE;
        }
        if (opts.help()) {
            io.out().println(CliOptions.USAGE);
            return 0;
        }
        Path cwd = Path.of(opts.cwd().orElse(System.getProperty("user.dir"))).toAbsolutePath().normalize();
        Path paiHome = Optional.ofNullable(env.get("PAI_HOME")).map(Path::of)
            .orElse(Path.of(System.getProperty("user.home"), ".pai"));
        Layout layout =
            new Layout(cwd, paiHome, EnvConfig.load(env, cwd, paiHome), "1".equals(env.get("PAI_DEBUG_HTTP")));
        ProviderSetup.Selected selected;
        ModelCatalog catalog;
        try {
            catalog = ModelCatalog.load(paiHome.resolve(ModelCatalog.FILE_NAME));
            selected =
                ProviderSetup.withCatalogLimits(ProviderSetup.select(opts, layout.cfg(), layout.debugHttp()), catalog);
        } catch (IllegalArgumentException e) {
            io.err().println(e.getMessage());
            return EXIT_USAGE;
        }
        SessionStore store = new JsonlSessionStoreImpl(paiHome.resolve("sessions"));
        Optional<SessionId> resumed = resume(opts, store, cwd, io.err());
        if (opts.resume() && resumed.isEmpty()) {
            return EXIT_USAGE;
        }
        Startup startup = new Startup(layout, store, selected, catalog, resumed);
        Repl repl = buildRepl(startup, io);
        announce(repl, opts, startup);
        return runRepl(repl, io, resumed.isPresent());
    }

    private static Repl buildRepl(Startup s, Io io) {
        Layout l = s.layout();
        AtomicReference<SessionId> session =
            new AtomicReference<>(s.resumed().orElseGet(() -> s.store().create(l.cwd())));
        ExtensionLoader loader = new ExtensionLoader();
        List<Extension> extensions = new ArrayList<>(loader.loadFromServiceLoader());
        extensions.add(new SkillsExtension(
            new SkillRegistry(List.of(l.paiHome().resolve("skills"), l.cwd().resolve(".pai").resolve("skills")))));
        ExtensionHost host = loader.install(extensions, l.paiHome());
        AtomicReference<Repl> replRef = new AtomicReference<>();
        CompactingHooks compacting = new CompactingHooks(new CharsTokenEstimatorImpl(), CompactionPolicy.defaults(),
            new ModelSummarizerImpl(() -> replRef.get().current().provider()), s.store(),
            new CompactingHooks.Target(session::get, () -> replRef.get().leaf()));
        AgentConfig config = AgentConfig.of(s.selected().model(), systemPrompt(l.cwd(), l.paiHome()));
        Renderer renderer = io.interactive() && io.in() == System.in && io.out() == System.out
            ? RendererFactory.create() : new PlainRendererImpl(io.in(), inputCharset(), io.out());
        Repl repl = new Repl(new Repl.Setup(host, s.store(), session, config, compacting, s.catalog(), l.cfg(),
            l.debugHttp(), l.cwd(), l.paiHome()), s.selected(), renderer);
        replRef.set(repl);
        Commands.builtins(repl).forEach(host::registerCommand);
        return repl;
    }

    private static void announce(Repl repl, CliOptions opts, Startup s) {
        ModelRef m = s.selected().model();
        SessionId sid = repl.session();
        String resumed = s.resumed().isPresent()
            ? " resumed=" + s.store().buildContext(sid, s.store().leafId(sid).orElse("")).size() + " messages" : "";
        repl.renderer().println("model=" + m.providerId() + "/" + m.modelId() + " session=" + sid.value() + " cwd="
            + s.layout().cwd() + resumed);
        if (ProviderSetup.configuredFromCwdEnv(opts, s.layout().cfg())) {
            repl.renderer().println("! provider config loaded from ./.env (base URL " + m.baseUrl()
                + ") - only run pai in directories you trust");
        }
    }

    private static int runRepl(Repl repl, Io io, boolean resumed) {
        ExtensionHost host = repl.setup().host();
        Renderer renderer = repl.renderer();
        host.dispatch(new SessionEvent.SessionStarted(repl.session(), resumed));
        if (io.interactive()) {
            renderer.onInterrupt(() -> {
                if (!repl.interrupt()) {
                    renderer.close();
                    System.exit(EXIT_INTERRUPTED);
                }
            });
        }
        try {
            repl.run();
            return 0;
        } catch (IOException e) {
            io.err().println("input error: " + e.getMessage());
            return 1;
        } finally {
            host.dispatch(new SessionEvent.SessionEnded(repl.session()));
            host.unloadAll();
            renderer.close();
        }
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
