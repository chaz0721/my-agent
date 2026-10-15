/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.session.SessionEntry;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class ResumedModelTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private static final Instant T0 = Instant.parse("2026-10-15T00:00:00Z");

    private static List<SessionEntry> branch(String... refs) {
        List<SessionEntry> out = new ArrayList<>();
        out.add(new SessionEntry.MessageEntry("m0", Optional.empty(), T0, UserMessage.text("hi")));
        int i = 0;
        for (String ref : refs) {
            String[] p = ref.split("/", 2);
            out.add(
                new SessionEntry.ModelChangeEntry("c" + i, Optional.of(out.get(out.size() - 1).id()), T0, p[0], p[1]));
            i++;
        }
        return out;
    }

    private ProviderSetup.Selected apply(String[] args, ModelCatalog catalog, List<SessionEntry> branch) {
        CliOptions opts = CliOptions.parse(args);
        EnvConfig cfg = EnvConfig.load(Map.of(), dir, dir, true);
        ProviderSetup.Selected startup =
            ProviderSetup.select(CliOptions.parse(new String[] {"--provider", "fake"}), cfg, false);
        return new ResumedModel(opts, cfg, catalog, ProviderSetup.Wiring.quiet(false)).apply(startup, branch,
            new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void latest_model_change_on_the_branch_wins() {
        ProviderSetup.Selected s = apply(new String[] {"--resume"}, ModelCatalog.empty(), branch("fake/a", "fake/b"));
        assertThat(s.model().modelId()).isEqualTo("b");
    }

    @Test
    void catalog_entry_supplies_limits() throws IOException {
        Path file = dir.resolve(ModelCatalog.FILE_NAME);
        Files.writeString(file, "[{\"providerId\":\"fake\",\"modelId\":\"big\",\"contextWindow\":1234}]");
        ProviderSetup.Selected s = apply(new String[] {"--resume"}, ModelCatalog.load(file), branch("fake/big"));
        assertThat(s.model().contextWindow()).isEqualTo(1234);
    }

    @Test
    void explicit_model_or_missing_change_keeps_the_startup_model() {
        assertThat(
            apply(new String[] {"--resume", "--model", "m"}, ModelCatalog.empty(), branch("fake/b")).model().modelId())
            .isEqualTo("echo");
        assertThat(apply(new String[] {"--resume"}, ModelCatalog.empty(), branch()).model().modelId())
            .isEqualTo("echo");
    }

    @Test
    void unknown_provider_keeps_the_startup_model_and_says_why() {
        ProviderSetup.Selected s = apply(new String[] {"--resume"}, ModelCatalog.empty(), branch("acme/x"));
        assertThat(s.model().modelId()).isEqualTo("echo");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("acme/x", "not in models.json");
    }
}
