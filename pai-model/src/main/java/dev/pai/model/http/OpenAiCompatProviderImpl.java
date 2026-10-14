/* pai-next — Apache-2.0 */
package dev.pai.model.http;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ChatRequest;
import dev.pai.model.ModelRef;
import dev.pai.model.Provider;
import dev.pai.model.StreamEvent;
import dev.pai.model.codec.openai.OpenAiCompletionsCodec;

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

    /**
     * Creates the provider.
     *
     * @param id provider id
     * @param apiKey API key, sent as a bearer token
     * @param baseUrl URL prefix, for example {@code https://api.deepseek.com}
     * @param sse transport
     * @param flags endpoint quirks
     */
    public OpenAiCompatProviderImpl(String id, String apiKey, URI baseUrl, SseClient sse, Flags flags) {
        this.id = id;
        this.apiKey = apiKey;
        this.endpoint = endpoint(baseUrl);
        this.call = new StreamingCall(flags.forceHttp1() ? sse.http1() : sse);
        this.codec = new OpenAiCompletionsCodec(id, flags.echoReasoning());
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
        OpenAiCompletionsCodec.StreamDecoder d = codec.newDecoder(request.model());
        Map<String, String> headers = Map.of("Authorization", "Bearer " + apiKey);
        StreamingCall.Request req = new StreamingCall.Request(request, endpoint, headers, codec.toRequestBody(request),
            StreamingCall.decoder(d::feed, d::finish, d::fail));
        return call.run(req, sink, cancel);
    }

    /**
     * Endpoint quirks.
     *
     * @param forceHttp1 restrict the transport to HTTP/1.1 (DeepSeek)
     * @param echoReasoning send reasoning text back on assistant messages (DeepSeek, Kimi)
     */
    public record Flags(boolean forceHttp1, boolean echoReasoning) {

        /** No quirks. */
        public static final Flags NONE = new Flags(false, false);
    }
}
