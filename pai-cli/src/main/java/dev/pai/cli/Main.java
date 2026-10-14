/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.loop.AgentConfig;
import dev.pai.core.loop.AgentLoop;
import dev.pai.core.prompt.SystemPromptBuilder;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionId;
import dev.pai.core.session.SessionStore;
import dev.pai.model.Message;

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
        boolean installSignal) {
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
        ProviderSetup.Selected selected;
        try {
            selected = ProviderSetup.select(opts, cfg, "1".equals(env.get("PAI_DEBUG_HTTP")));
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        }
        ExtensionLoader loader = new ExtensionLoader();
        ExtensionHost host = loader.install(loader.loadFromServiceLoader(), paiHome);
        Repl.builtins(selected.model()).forEach(host::registerCommand);
        SessionStore store = new JsonlSessionStoreImpl(paiHome.resolve("sessions"));
        Optional<SessionId> resumed = resume(opts, store, cwd, err);
        if (opts.resume() && resumed.isEmpty()) {
            return EXIT_USAGE;
        }
        SessionId session = resumed.orElseGet(() -> store.create(cwd));
        Optional<String> leaf = store.leafId(session);
        List<Message> history = leaf.map(l -> store.buildContext(session, l)).orElse(List.of());
        String prompt = new SystemPromptBuilder().base(SystemPromptBuilder.defaultBase()).section("Environment",
            "Working directory: " + cwd + "\nPlatform: " + System.getProperty("os.name") + "\nDate: " + LocalDate.now())
            .build();
        AgentLoop loop = new AgentLoop(selected.provider(), host.tools(), host.hooks(),
            AgentConfig.of(selected.model(), prompt), cwd);
        out.println(
            "model=" + selected.model().providerId() + "/" + selected.model().modelId() + " session=" + session.value()
                + " cwd=" + cwd + (resumed.isPresent() ? " resumed=" + history.size() + " messages" : ""));
        if (ProviderSetup.configuredFromCwdEnv(opts, cfg)) {
            out.println("! provider config loaded from ./.env (base URL " + selected.model().baseUrl()
                + ") - only run pai in directories you trust");
        }
        host.dispatch(new SessionEvent.SessionStarted(session, resumed.isPresent()));
        Repl repl = new Repl(new Repl.Setup(loop, host, store, session, selected.model()), history, leaf, out);
        if (installSignal) {
            Interrupts.install(repl, EXIT_INTERRUPTED);
        }
        try {
            repl.run(new BufferedReader(new InputStreamReader(in, inputCharset())));
        } catch (IOException e) {
            err.println("input error: " + e.getMessage());
            return 1;
        } finally {
            host.dispatch(new SessionEvent.SessionEnded(session));
            host.unloadAll();
        }
        return 0;
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
