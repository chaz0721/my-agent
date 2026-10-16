/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import dev.pai.core.compaction.CharsTokenEstimatorImpl;
import dev.pai.core.compaction.Compaction;
import dev.pai.core.compaction.CompactionPolicy;
import dev.pai.core.compaction.ModelSummarizerImpl;
import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.ext.UserInteraction;
import dev.pai.core.loop.AgentConfig;
import dev.pai.core.loop.AgentEvent;
import dev.pai.core.loop.TerminationReasonEnum;
import dev.pai.core.prompt.ProjectContext;
import dev.pai.core.prompt.SystemPromptBuilder;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionEntry;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.core.session.SessionTree;
import dev.pai.model.AssistantMessage;
import dev.pai.model.ChatOptions;
import dev.pai.model.ModelRef;
import dev.pai.model.preset.ProviderPresets;
import dev.pai.tui.JLineRendererImpl;
import dev.pai.tui.PlainRendererImpl;
import dev.pai.tui.Renderer;
import dev.pai.tui.RendererFactory;
import dev.pai.tui.TerminalInteractionImpl;

/** Entry point: loads extensions, picks the provider, opens or resumes a session and runs the REPL. */
public final class Main {

    /** Usage error (sysexits {@code EX_USAGE}); apart from the {@code --print} statuses 0 and 2 to 7. */
    static final int EXIT_USAGE = 64;
    private static final int EXIT_INTERRUPTED = 130;
    /** Environment variable that declares piped input able to answer questions (for scripted sessions). */
    static final String INTERACTIVE_ENV = "PAI_INTERACTIVE";
    /** Directory of session files below the pai home. */
    static final String SESSIONS_DIR = "sessions";
    /** Environment variable that sets the approval mode when {@code --approval} is not given. */
    static final String APPROVAL_ENV = "PAI_APPROVAL";
    /** Host option the approvals extension reads its mode from. */
    static final String APPROVAL_OPTION = "approval";
    /** Command of the approvals extension that Shift+Tab drives. */
    static final String MODE_COMMAND = "approvals";

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
     * @param wiring how providers are built
     * @param host what extensions are told: trust, interaction, notices and options
     */
    private record Layout(Path cwd, Path paiHome, EnvConfig cfg, ProviderSetup.Wiring wiring,
        ExtensionHost.Settings host) {

        boolean trusted() {
            return host.projectTrusted();
        }
    }

    /**
     * Everything resolved before the REPL is assembled.
     *
     * @param layout directories and configuration
     * @param store session storage
     * @param selected startup provider and model
     * @param catalog configured models
     * @param resumed the resumed session, if any
     * @param opts the command line
     */
    private record Startup(Layout layout, SessionStore store, ProviderSetup.Selected selected, ModelCatalog catalog,
        Optional<SessionId> resumed, CliOptions opts) {
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
            .orElse(Path.of(System.getProperty("user.home"), ".pai")).toAbsolutePath();
        Renderer renderer = opts.print().isEmpty() && io.interactive() && io.in() == System.in && io.out() == System.out
            ? RendererFactory.create()
            : new PlainRendererImpl(io.in(), inputCharset(), opts.quiet() ? io.err() : io.out());
        Optional<UserInteraction> ui = opts.print().isPresent() ? Optional.empty() : interaction(renderer, env);
        try {
            boolean trusted = new ProjectTrust(paiHome).decide(cwd, ui);
            ExtensionHost.Settings host =
                new ExtensionHost.Settings(paiHome, cwd, trusted, ui, m -> renderer.println("! " + m))
                    .withOptions(hostOptions(opts, env));
            Layout layout = new Layout(cwd, paiHome, EnvConfig.load(env, cwd, paiHome, trusted),
                new ProviderSetup.Wiring("1".equals(env.get("PAI_DEBUG_HTTP")), m -> renderer.println("! " + m)), host);
            return start(opts, layout, io, renderer);
        } finally {
            renderer.close();
        }
    }

    /** Run-time options for extensions: {@code approval} from {@code --approval}, else {@value #APPROVAL_ENV}. */
    static Map<String, String> hostOptions(CliOptions opts, Map<String, String> env) {
        Map<String, String> options = new HashMap<>();
        opts.approval().or(() -> Optional.ofNullable(env.get(APPROVAL_ENV)))
            .ifPresent(v -> options.put(APPROVAL_OPTION, v));
        Optional.ofNullable(env.get("PAI_SUBAGENT_MAX_CONCURRENCY"))
            .ifPresent(v -> options.put("subagent.max-concurrency", v));
        return options;
    }

    /**
     * Questions need a person at a real terminal (the JLine renderer); piped input never answers them unless
     * {@value #INTERACTIVE_ENV}{@code =1} says it will.
     */
    static Optional<UserInteraction> interaction(Renderer renderer, Map<String, String> env) {
        boolean person = renderer instanceof JLineRendererImpl || "1".equals(env.get(INTERACTIVE_ENV));
        return person ? Optional.of(new TerminalInteractionImpl(renderer)) : Optional.empty();
    }

    private static int start(CliOptions opts, Layout layout, Io io, Renderer renderer) {
        Path cwd = layout.cwd();
        Path paiHome = layout.paiHome();
        Startup startup;
        Repl repl;
        try {
            ModelCatalog catalog = ModelCatalog.load(paiHome.resolve(ModelCatalog.FILE_NAME),
                ProviderPresets.merge(ProviderPresets.builtin(), paiHome.resolve(ProviderPresets.USER_FILE)));
            ProviderSetup.Selected selected = ProviderSetup
                .withCatalogLimits(ProviderSetup.select(opts, layout.cfg(), layout.wiring(), catalog), catalog);
            SessionStore store = new JsonlSessionStoreImpl(paiHome.resolve(SESSIONS_DIR));
            Optional<SessionId> resumed = resume(opts, store, cwd, io.err());
            if (opts.resume() && resumed.isEmpty()) {
                return EXIT_USAGE;
            }
            if (resumed.isPresent()) {
                SessionId id = resumed.get();
                List<SessionEntry> branch =
                    store.leafId(id).map(leaf -> SessionTree.of(store.readAll(id)).path(leaf)).orElse(List.of());
                selected =
                    new ResumedModel(opts, layout.cfg(), catalog, layout.wiring()).apply(selected, branch, io.err());
            }
            startup = new Startup(layout, store, selected, catalog, resumed, opts);
            repl = buildRepl(startup, renderer);
        } catch (IllegalArgumentException e) {
            io.err().println(e.getMessage());
            return EXIT_USAGE;
        }
        announce(repl, startup);
        boolean resumed = startup.resumed().isPresent();
        return opts.print().isPresent() ? runPrint(repl, io, opts, resumed) : runRepl(repl, io, resumed);
    }

    private static Repl buildRepl(Startup s, Renderer renderer) {
        Layout l = s.layout();
        ExtensionLoader loader = new ExtensionLoader();
        List<Extension> extensions = new ArrayList<>(loader.loadFromServiceLoader());
        List<Path> dirs = new ArrayList<>(List.of(l.paiHome().resolve("extensions")));
        if (l.trusted()) {
            dirs.add(l.cwd().resolve(".pai").resolve("extensions"));
        }
        extensions.addAll(loader.loadFromDirs(dirs));
        Set<String> ids = extensions.stream().map(Extension::id).collect(Collectors.toCollection(TreeSet::new));
        Set<String> skip = s.opts().noExt();
        if (!ids.containsAll(skip) || skip.contains("hitl") || skip.contains("builtin-tools")) {
            throw new IllegalArgumentException("--no-ext " + String.join(",", skip) + ": hitl (approvals, path guards)"
                + " and builtin-tools cannot be skipped, unknown ids are refused; loaded: " + String.join(",", ids));
        }
        extensions.removeIf(e -> skip.contains(e.id()));
        AtomicReference<SessionId> session =
            new AtomicReference<>(s.resumed().orElseGet(() -> s.store().create(l.cwd())));
        AtomicReference<Repl> replRef = new AtomicReference<>();
        SessionEntriesImpl entries =
            new SessionEntriesImpl(s.store(), () -> Optional.ofNullable(replRef.get()).map(Repl::cursor),
                Clock.systemUTC(), SessionEntriesImpl.filesUnder(l.paiHome().resolve(SESSIONS_DIR)));
        ExtensionHost host = loader.install(extensions, l.host().withSession(entries)
            .withProvider(() -> Optional.ofNullable(replRef.get()).map(r -> r.current().provider())));
        Compaction compacting = new Compaction(new CharsTokenEstimatorImpl(), CompactionPolicy.defaults(),
            new ModelSummarizerImpl(() -> replRef.get().current().provider(), host.tools()), s.store(),
            new Compaction.Target(session::get))
            .withHost(l.host().notifier(), new FileRestorer(l.cwd(), l.host().notifier())::restore);
        AgentConfig config = new AgentConfig(s.selected().model(), systemPrompt(l), ChatOptions.defaults(),
            AgentConfig.DEFAULT_MAX_TOOL_CONCURRENCY, s.opts().maxTurns().orElse(AgentConfig.DEFAULT_MAX_TURNS));
        Repl repl = new Repl(new Repl.Setup(host, s.store(), session, config, compacting, s.catalog(), l.cfg(),
            l.wiring(), l.cwd(), l.paiHome()), s.selected(), renderer);
        replRef.set(repl);
        Commands.builtins(repl).forEach(host::registerCommand);
        if (host.command(MODE_COMMAND).isPresent()) {
            renderer.onModeCycle(() -> repl.command("/" + MODE_COMMAND + " cycle"));
        }
        return repl;
    }

    private static void announce(Repl repl, Startup s) {
        ModelRef m = s.selected().model();
        SessionId sid = repl.session();
        String resumed = s.resumed().isPresent()
            ? " resumed=" + s.store().buildContext(sid, s.store().leafId(sid).orElse("")).size() + " messages" : "";
        repl.renderer().println("model=" + m.providerId() + "/" + m.modelId() + " session=" + sid.value() + " cwd="
            + s.layout().cwd() + resumed);
        ProjectTrust.notices(s.layout().cwd(), s.layout().trusted(), repl.setup().host().statuses())
            .forEach(repl.renderer()::println);
    }

    private static int runRepl(Repl repl, Io io, boolean resumed) {
        ExtensionHost host = repl.setup().host();
        Renderer renderer = repl.renderer();
        host.dispatch(new SessionEvent.SessionStarted(repl.session(), resumed));
        Runnable end = sessionEnd(repl::session, host);
        if (io.interactive()) {
            renderer.onInterrupt(() -> onInterrupt(repl::interrupt, end, () -> {
                renderer.close();
                System.exit(EXIT_INTERRUPTED);
            }));
        }
        try {
            repl.run();
            return 0;
        } catch (IOException e) {
            io.err().println("input error: " + e.getMessage());
            return 1;
        } finally {
            end.run();
        }
    }

    /** {@code --print}: one run; with {@code --quiet} stdout gets only the final assistant text. */
    private static int runPrint(Repl repl, Io io, CliOptions opts, boolean resumed) {
        ExtensionHost host = repl.setup().host();
        host.dispatch(new SessionEvent.SessionStarted(repl.session(), resumed));
        try {
            AgentEvent.AgentEnd end = repl.prompt(opts.print().get());
            if (opts.quiet()) {
                end.newMessages().stream().filter(AssistantMessage.class::isInstance).reduce((a, b) -> b)
                    .ifPresent(m -> io.out().println(((AssistantMessage)m).text()));
            }
            return exitCode(end.reason());
        } finally {
            sessionEnd(repl::session, host).run();
        }
    }

    /** Exit status of a {@code --print} run (also in {@link CliOptions#USAGE}). */
    static int exitCode(TerminationReasonEnum reason) {
        return switch (reason) {
            case COMPLETED -> 0;
            case MAX_TURNS -> 2;
            case ABORTED -> 3;
            case PROMPT_TOO_LONG -> 4;
            case MAX_OUTPUT_TOKENS -> 5;
            case PROVIDER_ERROR -> 6;
            case HOOK_ERROR -> 7;
        };
    }

    /** Ends the session once: extensions see {@code SessionEnded} (memory candidates) before they are unloaded. */
    static Runnable sessionEnd(Supplier<SessionId> session, ExtensionHost host) {
        AtomicBoolean ended = new AtomicBoolean();
        return () -> {
            if (ended.compareAndSet(false, true)) {
                host.dispatch(new SessionEvent.SessionEnded(session.get()));
                host.unloadAll();
            }
        };
    }

    /** Ctrl+C: cancels what runs; when nothing runs, ends the session the normal way and then exits. */
    static void onInterrupt(BooleanSupplier interrupt, Runnable end, Runnable exit) {
        if (!interrupt.getAsBoolean()) {
            end.run();
            exit.run();
        }
    }

    private static String systemPrompt(Layout l) {
        SystemPromptBuilder b = new SystemPromptBuilder().base(SystemPromptBuilder.defaultBase()).section("Environment",
            "Working directory: " + l.cwd() + "\nPlatform: " + System.getProperty("os.name") + "\nDate: "
                + LocalDate.now());
        ProjectContext.load(l.paiHome(), l.cwd(), l.trusted()).ifPresent(t -> b.section(ProjectContext.SECTION, t));
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
