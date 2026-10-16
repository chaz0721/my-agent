/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dev.pai.core.session.SessionEntry;
import dev.pai.model.ModelRef;

/**
 * On {@code --resume}, switches to the model of the latest {@link SessionEntry.ModelChangeEntry} on the resumed branch,
 * unless {@code --provider} or {@code --model} was given. The model is looked up in the catalog first, then built like
 * the startup model for the built-in providers; when neither works the startup model stays and a line explains why.
 */
final class ResumedModel {

    private static final Set<String> BUILT_IN = Set.of("anthropic", "openai-compat", "fake");

    private final CliOptions opts;
    private final EnvConfig cfg;
    private final ModelCatalog catalog;
    private final ProviderSetup.Wiring wiring;

    ResumedModel(CliOptions opts, EnvConfig cfg, ModelCatalog catalog, ProviderSetup.Wiring wiring) {
        this.opts = opts;
        this.cfg = cfg;
        this.catalog = catalog;
        this.wiring = wiring;
    }

    /** Returns the model to continue with, given the startup choice and the branch root first. */
    ProviderSetup.Selected apply(ProviderSetup.Selected startup, List<SessionEntry> branch, PrintStream err) {
        if (opts.model().isPresent() || opts.provider().isPresent()) {
            return startup;
        }
        Optional<SessionEntry.ModelChangeEntry> last =
            branch.stream().filter(SessionEntry.ModelChangeEntry.class::isInstance)
                .map(SessionEntry.ModelChangeEntry.class::cast).reduce((a, b) -> b);
        if (last.isEmpty()) {
            return startup;
        }
        String ref = last.get().providerId() + "/" + last.get().modelId();
        ModelRef now = startup.model();
        if (ref.equals(now.providerId() + "/" + now.modelId())) {
            return startup;
        }
        try {
            Optional<ModelCatalog.Entry> entry = catalog.find(ref);
            if (entry.isPresent()) {
                return ProviderSetup.fromEntry(entry.get(), cfg, wiring);
            }
            if (BUILT_IN.contains(last.get().providerId())) {
                CliOptions o = new CliOptions(Optional.of(last.get().providerId()), Optional.of(last.get().modelId()),
                    opts.baseUrl(), opts.apiKey(), opts.resume(), opts.resumeId(), opts.cwd(), false, opts.approval(),
                    opts.print(), opts.quiet(), opts.maxTurns(), opts.noExt());
                return ProviderSetup.withCatalogLimits(ProviderSetup.select(o, cfg, wiring, catalog), catalog);
            }
            err.println("session used " + ref + ", which is not in " + ModelCatalog.FILE_NAME + "; continuing with "
                + now.providerId() + "/" + now.modelId());
        } catch (IllegalArgumentException e) {
            err.println("cannot restore " + ref + " (" + e.getMessage() + "); continuing with " + now.providerId() + "/"
                + now.modelId());
        }
        return startup;
    }
}
