/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import dev.pai.core.compaction.CharsTokenEstimatorImpl;
import dev.pai.core.compaction.Compaction;
import dev.pai.core.compaction.CompactionPolicy;
import dev.pai.core.compaction.ModelSummarizerImpl;
import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.loop.AgentConfig;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.loop.TerminationReasonEnum;
import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.core.session.SessionStore;
import dev.pai.model.AssistantMessage;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.FakeProviderImpl;
import dev.pai.model.Json;
import dev.pai.model.Message;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.Usage;
import dev.pai.tui.PlainRendererImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --print}: one run per reason through the REPL's prompt path with a fake provider, the exit status of each
 * reason, {@code --quiet} output, non-interactive input and {@code --no-ext}.
 */
class MainPrintTest {

    private static final ModelRef MODEL = new ModelRef(FakeProviderImpl.FAKE_ID, FakeProviderImpl.FAKE_ID,
        URI.create("http://fake.invalid"), 100_000, 1000, Set.of(ModelRef.CapabilityEnum.TOOLS));

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private static AssistantMessage message(List<ContentBlock> content, StopReasonEnum stop, Optional<String> error) {
        return new AssistantMessage(content, FakeProviderImpl.FAKE_ID, FakeProviderImpl.FAKE_ID, Usage.ZERO, stop,
            error, Instant.EPOCH);
    }

    private static List<ContentBlock> unknownToolCall() {
        return List.of(new ContentBlock.ToolCall("c1", "no_such_tool", Json.mapper().createObjectNode()));
    }

    /** A provider answer that makes the run end with the given reason. */
    private static Function<ChatRequest, AssistantMessage> responder(TerminationReasonEnum reason) {
        return switch (reason) {
            case COMPLETED, HOOK_ERROR ->
                r -> message(List.of(new ContentBlock.Text("done")), StopReasonEnum.STOP, Optional.empty());
            case MAX_TURNS -> r -> message(unknownToolCall(), StopReasonEnum.TOOL_USE, Optional.empty());
            case ABORTED -> r -> message(List.of(), StopReasonEnum.ABORTED, Optional.empty());
            case PROMPT_TOO_LONG -> r -> message(List.of(), StopReasonEnum.ERROR, Optional.of("prompt is too long"));
            case MAX_OUTPUT_TOKENS -> r -> message(unknownToolCall(), StopReasonEnum.LENGTH, Optional.empty());
            case PROVIDER_ERROR -> r -> message(List.of(), StopReasonEnum.ERROR, Optional.of("HTTP 500 boom"));
        };
    }

    /** An extension whose transformContext throws. */
    private static Extension failingHooks() {
        return new Extension() {
            @Override
            public String id() {
                return "failing-hooks";
            }

            @Override
            public void onLoad(ExtensionContext ctx) {
                ctx.addHooks(new AgentHooks() {
                    @Override
                    public List<Message> transformContext(List<Message> messages, LoopState state) {
                        throw new IllegalStateException("hook broke");
                    }
                });
            }
        };
    }

    private Repl repl(FakeProviderImpl provider, List<Extension> extra, int maxTurns) {
        SessionStore store = new JsonlSessionStoreImpl(home.resolve("sessions"));
        AtomicReference<dev.pai.core.session.SessionId> session = new AtomicReference<>(store.create(cwd));
        AtomicReference<Repl> ref = new AtomicReference<>();
        SessionEntriesImpl entries =
            new SessionEntriesImpl(store, () -> Optional.ofNullable(ref.get()).map(Repl::cursor), Clock.systemUTC(),
                SessionEntriesImpl.filesUnder(home.resolve("sessions")));
        ExtensionLoader loader = new ExtensionLoader();
        List<Extension> extensions = new ArrayList<>(loader.loadFromServiceLoader());
        extensions.addAll(extra);
        ExtensionHost host = loader.install(extensions, ExtensionHost.Settings.of(home, cwd).withSession(entries));
        Compaction compacting = new Compaction(new CharsTokenEstimatorImpl(), CompactionPolicy.defaults(),
            new ModelSummarizerImpl(() -> provider, new dev.pai.core.tool.ToolRegistry()), store,
            new Compaction.Target(session::get));
        AgentConfig config = new AgentConfig(MODEL, "system", ChatOptions.defaults(), 1, maxTurns);
        Repl repl = new Repl(
            new Repl.Setup(host, store, session, config, compacting, ModelCatalog.load(home.resolve("models.json")),
                EnvConfig.load(Map.of(), cwd, home, true), new ProviderSetup.Wiring(false, m -> {
                }), cwd, home),
            new ProviderSetup.Selected(provider, MODEL), new PlainRendererImpl(new ByteArrayInputStream(new byte[0]),
                StandardCharsets.UTF_8, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        ref.set(repl);
        return repl;
    }

    @ParameterizedTest
    @EnumSource(TerminationReasonEnum.class)
    void every_end_reason_has_its_exit_status(TerminationReasonEnum reason) {
        FakeProviderImpl provider = new FakeProviderImpl(FakeProviderImpl.FAKE_ID, responder(reason));
        List<Extension> extra = reason == TerminationReasonEnum.HOOK_ERROR ? List.of(failingHooks()) : List.of();
        Repl repl = repl(provider, extra, reason == TerminationReasonEnum.MAX_TURNS ? 1 : 10);
        TerminationReasonEnum actual = repl.prompt("go").reason();
        assertThat(actual).isEqualTo(reason);
        int expected = switch (reason) {
            case COMPLETED -> 0;
            case MAX_TURNS -> 2;
            case ABORTED -> 3;
            case PROMPT_TOO_LONG -> 4;
            case MAX_OUTPUT_TOKENS -> 5;
            case PROVIDER_ERROR -> 6;
            case HOOK_ERROR -> 7;
        };
        assertThat(Main.exitCode(actual)).isEqualTo(expected);
    }

    private record Result(int exit, String out, String err) {
    }

    /** Runs the real entry point with input that never answers (not a terminal). */
    private Result main(String... args) {
        return main(Map.of(), args);
    }

    private Result main(Map<String, String> extraEnv, String... args) {
        Map<String, String> env = new HashMap<>(extraEnv);
        env.put("PAI_HOME", home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] full = Stream.concat(Stream.of("--provider", "fake", "--cwd", cwd.toString()), Stream.of(args))
            .toArray(String[]::new);
        int code = Main.run(full,
            new Main.Io(new ByteArrayInputStream("/quit\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                false),
            env);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void quiet_print_writes_only_the_final_text_and_records_the_session() throws IOException {
        Result r = main("--print", "hi", "--quiet", "--approval", "yolo");
        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEqualTo("echo: hi" + System.lineSeparator());
        assertThat(r.err()).contains("model=fake/echo");
        try (Stream<Path> s = Files.walk(home.resolve("sessions"))) {
            Path file = s.filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow();
            assertThat(Files.readAllLines(file)).hasSize(3);
        }
    }

    @Test
    void multi_line_prompt_and_plain_output_without_quiet() {
        // a prompt file goes in as -p "$(cat file)" (--print-file was removed for the cli line budget)
        Result r = main("-p", "ignored", "-p", "from\n\"file\" 'x'", "--max-turns", "3");
        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("model=fake/echo", "echo: from", "\"file\" 'x'").doesNotContain("> ");
    }

    @Test
    void bad_print_options_are_usage_errors() {
        assertThat(main("--print-file", "prompt.txt").exit()).isEqualTo(64);
        assertThat(main("--print", "x", "--max-turns", "0").exit()).isEqualTo(Main.EXIT_USAGE).isEqualTo(64);
        assertThat(main("--print", "x", "--max-turns", "many").exit()).isEqualTo(64);
        assertThat(main("--help").out()).contains("--print", "--approval yolo", "7 hook error", "64 usage error",
            "1 crash");
    }

    @Test
    void print_ignores_an_interactive_declaration_and_never_asks() {
        // without --approval yolo the echo reply needs no tool; the point is that no question can block the run
        Result r = main(Map.of(Main.INTERACTIVE_ENV, "1"), "--print", "hi", "--quiet");
        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEqualTo("echo: hi" + System.lineSeparator());
        assertThat(Main.interaction(
            new PlainRendererImpl(new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)),
            Map.of(Main.INTERACTIVE_ENV, "1"))).isPresent();
    }

    @Test
    void no_ext_refuses_hitl_and_builtin_tools_and_unknown_ids() throws IOException {
        for (String skip : List.of("hitl", "builtin-tools", "tasks,hitl", "taks")) {
            Result r = main("--print", "hi", "--approval", "yolo", "--no-ext", skip);
            assertThat(r.exit()).as(skip).isEqualTo(64);
            assertThat(r.err()).as(skip).contains("cannot be skipped, unknown ids are refused", "loaded: ");
        }
        if (Files.exists(home.resolve("sessions"))) {
            try (Stream<Path> s = Files.walk(home.resolve("sessions"))) {
                assertThat(s.filter(p -> p.toString().endsWith(".jsonl")))
                    .as("no session is created for a refused command line").isEmpty();
            }
        }
        assertThat(main("--print", "hi", "--approval", "yolo", "--no-ext", "tasks,verify,subagent,skills").exit())
            .isZero();
    }

    private String repl(String... extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] args = Stream.concat(Stream.of("--provider", "fake", "--cwd", cwd.toString()), Stream.of(extra))
            .toArray(String[]::new);
        Main.run(args,
            new Main.Io(new ByteArrayInputStream("/extensions\n/todo\n/quit\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()),
                false),
            Map.of("PAI_HOME", home.toString()));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void no_ext_skips_extensions_by_id() {
        assertThat(repl()).contains("loaded   tasks", "loaded   skills").doesNotContain("unknown command: /todo");
        assertThat(repl("--no-ext", "tasks,skills"))
            .contains("unknown command: /todo", "loaded   hitl", "loaded   verify")
            .doesNotContain("loaded   tasks", "loaded   skills");
    }
}
