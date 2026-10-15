/* pai-next — Apache-2.0 */
package dev.pai.core.ext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.session.SessionEntry;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.Json;
import dev.pai.model.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionContextSettingsTest {

    private static final int THREADS = 8;
    private static final int ROUNDS = 200;

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "test", JsonSchema.object().build());
            }

            @Override
            public ToolEffectEnum effect() {
                return ToolEffectEnum.READ_ONLY;
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolContext ctx) {
                return ToolResult.text("ok");
            }
        };
    }

    private static Extension capturing(AtomicReference<ExtensionContext> ref) {
        return new Extension() {
            @Override
            public String id() {
                return "probe";
            }

            @Override
            public void onLoad(ExtensionContext ctx) {
                ref.set(ctx);
            }
        };
    }

    @Test
    void settings_reach_the_context() {
        AtomicReference<ExtensionContext> ref = new AtomicReference<>();
        List<String> notices = new ArrayList<>();
        UserInteraction ui = new UserInteraction() {
            @Override
            public String ask(String prompt, List<String> choices) {
                return choices.get(0);
            }

            @Override
            public boolean confirm(String prompt) {
                return true;
            }
        };
        Path home = Path.of("home").toAbsolutePath();
        Path cwd = Path.of("work").toAbsolutePath();
        new ExtensionLoader().install(List.of(capturing(ref)),
            new ExtensionHost.Settings(home, cwd, false, Optional.of(ui), notices::add));
        ExtensionContext ctx = ref.get();
        ctx.notify("hello");
        assertThat(ctx.paiHome()).isEqualTo(home);
        assertThat(ctx.cwd()).isEqualTo(cwd);
        assertThat(ctx.configDir()).isEqualTo(home.resolve("ext").resolve("probe"));
        assertThat(ctx.projectTrusted()).isFalse();
        assertThat(ctx.ui()).containsSame(ui);
        assertThat(notices).containsExactly("hello");
    }

    @Test
    void options_tools_and_session_entries_reach_the_context() {
        AtomicReference<ExtensionContext> ref = new AtomicReference<>();
        List<String> appended = new ArrayList<>();
        SessionEntry.CustomEntry stored = new SessionEntry.CustomEntry("e1", Optional.empty(), Instant.EPOCH, "tasks",
            Json.mapper().createObjectNode());
        SessionAccess access = new SessionAccess() {
            @Override
            public Optional<String> append(String customType, JsonNode data) {
                appended.add(customType + " " + data);
                return Optional.of("e1");
            }

            @Override
            public Optional<SessionEntry.CustomEntry> latest(String customType) {
                return "tasks".equals(customType) ? Optional.of(stored) : Optional.empty();
            }
        };
        ExtensionHost host = new ExtensionLoader().install(List.of(capturing(ref)), ExtensionHost.Settings
            .of(Path.of("h"), Path.of("c")).withOptions(Map.of("approval", "plan")).withSession(access));
        ExtensionContext ctx = ref.get();
        host.tools().register(tool("read"), "other");
        assertThat(ctx.option("approval")).contains("plan");
        assertThat(ctx.option("missing")).isEmpty();
        assertThat(ctx.tool("read")).isPresent();
        assertThat(ctx.tool("none")).isEmpty();
        ctx.appendEntry("tasks", Json.mapper().createObjectNode().put("n", 1));
        assertThat(appended).containsExactly("tasks {\"n\":1}");
        assertThat(ctx.latestEntry("tasks")).containsSame(stored);
        assertThat(ctx.latestEntry("other")).isEmpty();
    }

    @Test
    void the_short_constructor_has_no_options_and_no_session() {
        AtomicReference<ExtensionContext> ref = new AtomicReference<>();
        ExtensionHost.Settings settings =
            new ExtensionHost.Settings(Path.of("h"), Path.of("c"), true, Optional.empty(), m -> {
            });
        new ExtensionLoader().install(List.of(capturing(ref)), settings);
        assertThat(settings.options()).isEmpty();
        assertThat(ref.get().option("approval")).isEmpty();
        ref.get().appendEntry("tasks", Json.mapper().createObjectNode());
        assertThat(ref.get().latestEntry("tasks")).isEmpty();
    }

    @Test
    void a_failing_notifier_does_not_reach_the_extension() {
        AtomicReference<ExtensionContext> ref = new AtomicReference<>();
        new ExtensionLoader().install(List.of(capturing(ref)),
            new ExtensionHost.Settings(Path.of("h"), Path.of("c"), true, Optional.empty(), m -> {
                throw new IllegalStateException("closed");
            }));
        ref.get().notify("x");
        assertThat(ref.get().ui()).isEmpty();
    }

    @Test
    void the_legacy_install_trusts_and_has_no_ui() {
        AtomicReference<ExtensionContext> ref = new AtomicReference<>();
        new ExtensionLoader().install(List.of(capturing(ref)), Path.of("h"), Path.of("c"));
        assertThat(ref.get().projectTrusted()).isTrue();
        assertThat(ref.get().ui()).isEmpty();
    }

    @Test
    void tools_can_be_registered_and_removed_from_many_threads() throws Exception {
        AtomicReference<ExtensionContext> ref = new AtomicReference<>();
        ExtensionHost host = new ExtensionLoader().install(List.of(capturing(ref)), Path.of("h"), Path.of("c"));
        ExtensionContext ctx = ref.get();
        List<Future<?>> done = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < THREADS; t++) {
                String prefix = "t" + t + "_";
                done.add(pool.submit(() -> {
                    for (int i = 0; i < ROUNDS; i++) {
                        ctx.registerTool(tool(prefix + i));
                        ctx.unregisterTool(prefix + i);
                    }
                    ctx.registerTool(tool(prefix + "last"));
                }));
            }
            for (Future<?> f : done) {
                f.get();
            }
        }
        assertThat(host.tools().all()).hasSize(THREADS);
        assertThat(host.statuses()).singleElement().matches(ExtensionStatus::enabled);
    }
}
