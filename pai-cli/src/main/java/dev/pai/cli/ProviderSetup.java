/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import dev.pai.model.FakeProviderImpl;
import dev.pai.model.ModelRef;
import dev.pai.model.Provider;
import dev.pai.model.http.AnthropicProviderImpl;
import dev.pai.model.http.OpenAiCompatProviderImpl;
import dev.pai.model.http.SseClient;
import dev.pai.model.preset.ProviderFlags;
import dev.pai.model.preset.ProviderPreset;
import dev.pai.model.preset.ProviderPresets;

/**
 * Builds the provider and model from options and configuration: the built-in {@code anthropic}, {@code openai-compat}
 * and {@code fake} providers, or any provider preset. Every HTTP provider is wrapped in {@link RetryingProviderImpl}.
 */
final class ProviderSetup {

    static final String ANTHROPIC_KEY = "PAI_ANTHROPIC_API_KEY";
    static final String OPENAI_KEY = "PAI_OPENAI_COMPAT_API_KEY";
    static final String OPENAI_BASE_URL = "PAI_OPENAI_COMPAT_BASE_URL";
    static final String ANTHROPIC_BASE_URL = "PAI_ANTHROPIC_BASE_URL";
    static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-5";
    static final String DEFAULT_OPENAI_MODEL = "deepseek-chat";
    static final String DEFAULT_OPENAI_BASE_URL = "https://api.deepseek.com";
    private static final String KEY_SUFFIX = "_API_KEY";
    private static final String URL_SUFFIX = "_BASE_URL";
    private static final int CONTEXT_WINDOW = 128_000;
    private static final int MAX_OUTPUT_TOKENS = 8192;

    /**
     * Provider plus the model to use with it.
     *
     * @param provider the provider
     * @param model the model
     */
    record Selected(Provider provider, ModelRef model) {
    }

    private ProviderSetup() {}

    /**
     * Returns whether the provider choice or its key came from {@code <cwd>/.env} (only read in a trusted project), so
     * that the user is reminded where the configuration comes from.
     */
    static boolean configuredFromCwdEnv(CliOptions opts, EnvConfig cfg, ModelCatalog catalog) {
        boolean choiceFromCwd = opts.provider().isEmpty() && cfg.layerOf(ANTHROPIC_KEY) == EnvConfig.CWD_LAYER;
        String provider = opts.provider().orElse(cfg.get(ANTHROPIC_KEY).isPresent() ? "anthropic" : "openai-compat");
        List<String> keys = switch (provider) {
            case "anthropic" -> List.of(ANTHROPIC_KEY);
            case "openai-compat" -> List.of(OPENAI_KEY);
            default ->
                ProviderPresets.find(catalog.presets(), provider).map(ProviderPreset::apiKeyEnv).orElse(List.of());
        };
        Optional<String> used = keys.stream().filter(k -> cfg.layerOf(k) >= 0).findFirst();
        boolean keyFromCwd =
            opts.apiKey().isEmpty() && used.map(k -> cfg.layerOf(k) == EnvConfig.CWD_LAYER).orElse(false);
        return choiceFromCwd || keyFromCwd;
    }

    static Selected select(CliOptions opts, EnvConfig cfg, boolean debugHttp) {
        return select(opts, cfg, debugHttp, ModelCatalog.empty());
    }

    static Selected select(CliOptions opts, EnvConfig cfg, boolean debugHttp, ModelCatalog catalog) {
        String id = opts.provider().orElseGet(() -> cfg.get(ANTHROPIC_KEY).isPresent() ? "anthropic" : "openai-compat");
        SseClient sse = sse(debugHttp);
        return switch (id) {
            case "anthropic" -> {
                EnvConfig.Credentials c = credentials(opts, cfg, ANTHROPIC_KEY, ANTHROPIC_BASE_URL);
                URI base = URI.create(c.baseUrl().orElse(AnthropicProviderImpl.DEFAULT_BASE_URL));
                String model = opts.model().orElse(DEFAULT_ANTHROPIC_MODEL);
                Provider p = new AnthropicProviderImpl(c.apiKey(), base, sse);
                yield new Selected(new RetryingProviderImpl(p), model(id, model, base, ProviderFlags.NONE));
            }
            case "openai-compat" -> {
                EnvConfig.Credentials c = credentials(opts, cfg, OPENAI_KEY, OPENAI_BASE_URL);
                URI base = URI.create(c.baseUrl().orElse(DEFAULT_OPENAI_BASE_URL));
                ProviderFlags flags = flagsForHost(catalog.presets(), base);
                String model = opts.model().orElse(DEFAULT_OPENAI_MODEL);
                Provider p = new OpenAiCompatProviderImpl(id, c.apiKey(), base, sse, flags);
                yield new Selected(new RetryingProviderImpl(p), model(id, model, base, flags));
            }
            case "fake" -> new Selected(FakeProviderImpl.echo(), model(FakeProviderImpl.FAKE_ID,
                opts.model().orElse("echo"), URI.create("http://localhost"), ProviderFlags.NONE));
            default -> preset(opts, cfg, debugHttp, catalog, id);
        };
    }

    /** {@code --provider <preset>}: the preset's first model unless {@code --model} names another. */
    private static Selected preset(CliOptions opts, EnvConfig cfg, boolean debugHttp, ModelCatalog catalog, String id) {
        ProviderPreset p = ProviderPresets.find(catalog.presets(), id)
            .orElseThrow(() -> new IllegalArgumentException("unknown provider: " + id));
        String modelId = opts.model().orElse(p.models().get(0).modelId());
        ModelCatalog.Entry e = catalog.find(id + "/" + modelId).orElseGet(
            () -> new ModelCatalog.Entry(model(id, modelId, p.baseUrl(), p.flags()), p.apiKeyEnv(), true, p.flags()));
        if (opts.apiKey().isPresent()) {
            Optional<String> url = opts.baseUrl().or(() -> p.apiKeyEnv().stream()
                .map(k -> cfg.environmentValue(baseUrlName(k))).flatMap(Optional::stream).findFirst());
            return build(e, new EnvConfig.Credentials(opts.apiKey().get(), url), debugHttp);
        }
        EnvConfig.Credentials c = credentials(e, cfg);
        return build(e, new EnvConfig.Credentials(c.apiKey(), opts.baseUrl().or(c::baseUrl)), debugHttp);
    }

    /**
     * Key: command line, then configuration layers. Base URL: command line, then environment, then only the .env file
     * that supplied the key; otherwise the provider default.
     */
    private static EnvConfig.Credentials credentials(CliOptions opts, EnvConfig cfg, String keyName, String urlName) {
        if (opts.apiKey().isPresent()) {
            return new EnvConfig.Credentials(opts.apiKey().get(),
                opts.baseUrl().or(() -> cfg.environmentValue(urlName)));
        }
        EnvConfig.Credentials c =
            cfg.credentials(keyName, urlName).orElseThrow(() -> new IllegalArgumentException("missing " + keyName));
        return new EnvConfig.Credentials(c.apiKey(), opts.baseUrl().or(c::baseUrl));
    }

    /**
     * Credentials of a catalog entry: the first of its key variables that is set, with the {@code <PREFIX>_BASE_URL} of
     * the same layer when the entry's base URL is a preset default. A shared key (not the first variable) is only taken
     * when that base URL is unset or on the preset's host, so it never travels to another endpoint.
     */
    static EnvConfig.Credentials credentials(ModelCatalog.Entry e, EnvConfig cfg) {
        List<String> keys = e.apiKeyEnv();
        for (int i = 0; i < keys.size(); i++) {
            Optional<EnvConfig.Credentials> c = cfg.credentials(keys.get(i), baseUrlName(keys.get(i)));
            if (c.isEmpty()) {
                continue;
            }
            Optional<String> url = e.presetBase() ? c.get().baseUrl() : Optional.empty();
            if (i > 0 && url.isPresent() && !sameHost(URI.create(url.get()), e.model().baseUrl())) {
                continue;
            }
            return new EnvConfig.Credentials(c.get().apiKey(), url);
        }
        if (keys.isEmpty()) {
            return new EnvConfig.Credentials("", Optional.empty());
        }
        throw new IllegalArgumentException("missing " + String.join(" or ", keys));
    }

    /** Builds the provider of a catalog entry. */
    static Selected fromEntry(ModelCatalog.Entry e, EnvConfig cfg, boolean debugHttp) {
        if (FakeProviderImpl.FAKE_ID.equals(e.providerId())) {
            return new Selected(FakeProviderImpl.echo(), e.model());
        }
        return build(e, credentials(e, cfg), debugHttp);
    }

    private static Selected build(ModelCatalog.Entry e, EnvConfig.Credentials c, boolean debugHttp) {
        ModelRef m = e.model();
        URI base = c.baseUrl().map(URI::create).orElse(m.baseUrl());
        ModelRef rebased =
            new ModelRef(m.providerId(), m.modelId(), base, m.contextWindow(), m.maxOutputTokens(), m.capabilities());
        Provider p = "anthropic".equals(e.providerId()) ? new AnthropicProviderImpl(c.apiKey(), base, sse(debugHttp))
            : new OpenAiCompatProviderImpl(e.providerId(), c.apiKey(), base, sse(debugHttp), e.flags());
        return new Selected(new RetryingProviderImpl(p), rebased);
    }

    /**
     * Applies the window, output limit and capabilities a catalog entry declares, exactly as {@link #fromEntry} does.
     */
    static Selected withCatalogLimits(Selected s, ModelCatalog catalog) {
        ModelRef m = s.model();
        return catalog.find(m.providerId() + "/" + m.modelId()).map(ModelCatalog.Entry::model)
            .map(e -> new Selected(s.provider(), new ModelRef(m.providerId(), m.modelId(), m.baseUrl(),
                e.contextWindow(), e.maxOutputTokens(), e.capabilities())))
            .orElse(s);
    }

    /** {@code DEEPSEEK_API_KEY} pairs with {@code DEEPSEEK_BASE_URL}. */
    static String baseUrlName(String keyName) {
        String prefix =
            keyName.endsWith(KEY_SUFFIX) ? keyName.substring(0, keyName.length() - KEY_SUFFIX.length()) : keyName;
        return prefix + URL_SUFFIX;
    }

    /** The quirks of the preset whose host the generic openai-compat endpoint points at, if any. */
    private static ProviderFlags flagsForHost(List<ProviderPreset> presets, URI base) {
        return presets.stream().filter(p -> sameHost(p.baseUrl(), base)).map(ProviderPreset::flags).findFirst()
            .orElse(ProviderFlags.NONE);
    }

    private static boolean sameHost(URI a, URI b) {
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost());
    }

    private static SseClient sse(boolean debugHttp) {
        return new SseClient(SseClient.DEFAULT_CONNECT_TIMEOUT, SseClient.DEFAULT_RESPONSE_TIMEOUT, false, debugHttp);
    }

    private static ModelRef model(String providerId, String modelId, URI base, ProviderFlags flags) {
        Set<ModelRef.CapabilityEnum> caps = EnumSet.noneOf(ModelRef.CapabilityEnum.class);
        if (flags.sendTools()) {
            caps.add(ModelRef.CapabilityEnum.TOOLS);
        }
        String lower = modelId.toLowerCase(Locale.ROOT);
        if (lower.contains("reasoner") || lower.contains("thinking")) {
            caps.add(ModelRef.CapabilityEnum.THINKING);
        }
        if (lower.startsWith("claude") || flags.imageInput()) {
            caps.add(ModelRef.CapabilityEnum.IMAGE_INPUT);
        }
        return new ModelRef(providerId, modelId, base, CONTEXT_WINDOW, MAX_OUTPUT_TOKENS, caps);
    }
}
