/* pai-next — Apache-2.0 */
package dev.pai.model.http;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatRequest;
import dev.pai.model.ModelRef;
import dev.pai.model.Provider;
import dev.pai.model.StreamEvent;
import dev.pai.model.codec.StreamDecoder;
import dev.pai.model.codec.openai.OpenAiCompletionsCodec;
import dev.pai.model.preset.ProviderFlags;

/**
 * Provider for any OpenAI Chat Completions compatible endpoint. The endpoint is the base URL prefix (trailing slashes
 * removed, {@code /v1} neither added nor removed) followed by {@code /chat/completions}.
 */
public final class OpenAiCompatProviderImpl implements Provider {

    /** Default provider id. */
    public static final String PROVIDER_ID = "openai-compat";

    private static final String COMPLETIONS_PATH = "/chat/completions";

    private final String id;
    private final String apiKey;
    private final URI endpoint;
    private final StreamingCall call;
    private final OpenAiCompletionsCodec codec;
    private final Map<String, String> extraHeaders;

    /**
     * Creates the provider.
     *
     * @param id provider id
     * @param apiKey API key, sent as a bearer token
     * @param baseUrl URL prefix, for example {@code https://api.deepseek.com}
     * @param sse transport
     * @param flags endpoint quirks; {@code imageInput} and {@code sendTools} act through the model capabilities
     */
    public OpenAiCompatProviderImpl(String id, String apiKey, URI baseUrl, SseClient sse, ProviderFlags flags) {
        this.id = id;
        this.apiKey = apiKey;
        this.endpoint = endpoint(baseUrl);
        this.call = new StreamingCall(flags.forceHttp1() ? sse.http1() : sse);
        this.codec = new OpenAiCompletionsCodec(id, flags.echoReasoning());
        this.extraHeaders = flags.extraHeaders();
    }

    /**
     * Resolves the completions endpoint for a base URL prefix.
     *
     * @param baseUrl the prefix
     * @return the full endpoint
     */
    public static URI endpoint(URI baseUrl) {
        return StreamingCall.endpoint(baseUrl, COMPLETIONS_PATH);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<ModelRef.CapabilityEnum> capabilities(ModelRef model) {
        return model.capabilities();
    }

    @Override
    public AssistantMessage stream(ChatRequest request, Consumer<StreamEvent> sink, CancelToken cancel) {
        if (request == null || request.model() == null) {
            throw new IllegalArgumentException("request and model are required");
        }
        StreamDecoder d = codec.newDecoder(request.model());
        Map<String, String> headers = new LinkedHashMap<>(extraHeaders);
        headers.keySet().removeIf("Authorization"::equalsIgnoreCase);
        headers.put("Authorization", "Bearer " + apiKey);
        StreamingCall.Request req =
            new StreamingCall.Request(request, endpoint, headers, codec.toRequestBody(request), d);
        return call.run(req, sink, cancel);
    }
}
