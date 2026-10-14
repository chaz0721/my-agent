/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.net.URI;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import dev.pai.model.FakeProviderImpl;
import dev.pai.model.ModelRef;
import dev.pai.model.Provider;
import dev.pai.model.http.AnthropicProviderImpl;
import dev.pai.model.http.OpenAiCompatProviderImpl;
import dev.pai.model.http.SseClient;

/**
 * Builds the provider and model from options and configuration.
 */
final class ProviderSetup {

    static final String ANTHROPIC_KEY = "PAI_ANTHROPIC_API_KEY";
    static final String OPENAI_KEY = "PAI_OPENAI_COMPAT_API_KEY";
    static final String OPENAI_BASE_URL = "PAI_OPENAI_COMPAT_BASE_URL";
    static final String ANTHROPIC_BASE_URL = "PAI_ANTHROPIC_BASE_URL";
    static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-5";
    static final String DEFAULT_OPENAI_MODEL = "deepseek-chat";
    static final String DEFAULT_OPENAI_BASE_URL = "https://api.deepseek.com";
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

    static Selected select(CliOptions opts, EnvConfig cfg, boolean debugHttp) {
        String id = opts.provider().orElseGet(() -> cfg.get(ANTHROPIC_KEY).isPresent() ? "anthropic" : "openai-compat");
        SseClient sse =
            new SseClient(SseClient.DEFAULT_CONNECT_TIMEOUT, SseClient.DEFAULT_RESPONSE_TIMEOUT, false, debugHttp);
        return switch (id) {
            case "anthropic" -> {
                EnvConfig.Credentials c = credentials(opts, cfg, ANTHROPIC_KEY, ANTHROPIC_BASE_URL);
                String key = c.apiKey();
                URI base = URI.create(c.baseUrl().orElse(AnthropicProviderImpl.DEFAULT_BASE_URL));
                String model = opts.model().orElse(DEFAULT_ANTHROPIC_MODEL);
                yield new Selected(new AnthropicProviderImpl(key, base, sse), model(id, model, base));
            }
            case "openai-compat" -> {
                EnvConfig.Credentials c = credentials(opts, cfg, OPENAI_KEY, OPENAI_BASE_URL);
                String key = c.apiKey();
                URI base = URI.create(c.baseUrl().orElse(DEFAULT_OPENAI_BASE_URL));
                boolean deepseek = base.getHost() != null && base.getHost().endsWith("deepseek.com");
                OpenAiCompatProviderImpl.Flags flags = new OpenAiCompatProviderImpl.Flags(deepseek, true);
                String model = opts.model().orElse(DEFAULT_OPENAI_MODEL);
                yield new Selected(new OpenAiCompatProviderImpl(id, key, base, sse, flags), model(id, model, base));
            }
            case "fake" -> new Selected(FakeProviderImpl.echo(),
                model(FakeProviderImpl.FAKE_ID, opts.model().orElse("echo"), URI.create("http://localhost")));
            default -> throw new IllegalArgumentException("unknown provider: " + id);
        };
    }

    /** Command line key and URL form the highest layer; otherwise both come from one configuration layer. */
    private static EnvConfig.Credentials credentials(CliOptions opts, EnvConfig cfg, String keyName, String urlName) {
        if (opts.apiKey().isPresent()) {
            return new EnvConfig.Credentials(opts.apiKey().get(), opts.baseUrl());
        }
        return cfg.credentials(keyName, urlName).orElseThrow(() -> new IllegalArgumentException("missing " + keyName));
    }

    private static ModelRef model(String providerId, String modelId, URI base) {
        Set<ModelRef.CapabilityEnum> caps = EnumSet.of(ModelRef.CapabilityEnum.TOOLS);
        String lower = modelId.toLowerCase(Locale.ROOT);
        if (lower.contains("reasoner") || lower.contains("thinking")) {
            caps.add(ModelRef.CapabilityEnum.THINKING);
        }
        if (lower.startsWith("claude")) {
            caps.add(ModelRef.CapabilityEnum.IMAGE_INPUT);
        }
        return new ModelRef(providerId, modelId, base, CONTEXT_WINDOW, MAX_OUTPUT_TOKENS, caps);
    }
}
