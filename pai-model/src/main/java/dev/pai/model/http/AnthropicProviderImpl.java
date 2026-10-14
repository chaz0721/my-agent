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
import dev.pai.model.codec.anthropic.AnthropicMessagesCodec;

/**
 * Provider for the Anthropic Messages API. The endpoint is the base URL prefix followed by {@code /v1/messages}.
 */
public final class AnthropicProviderImpl implements Provider {

    /** Provider id. */
    public static final String PROVIDER_ID = "anthropic";
    /** Default base URL prefix. */
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    /** Value of the {@code anthropic-version} header. */
    public static final String API_VERSION = "2023-06-01";

    private static final String MESSAGES_PATH = "/v1/messages";

    private final String apiKey;
    private final URI endpoint;
    private final StreamingCall call;
    private final AnthropicMessagesCodec codec = new AnthropicMessagesCodec(PROVIDER_ID);

    /**
     * Creates the provider.
     *
     * @param apiKey API key, sent as {@code x-api-key}
     * @param baseUrl URL prefix, for example {@value #DEFAULT_BASE_URL}
     * @param sse transport
     */
    public AnthropicProviderImpl(String apiKey, URI baseUrl, SseClient sse) {
        this.apiKey = apiKey;
        this.endpoint = endpoint(baseUrl);
        this.call = new StreamingCall(sse);
    }

    /**
     * Resolves the messages endpoint for a base URL prefix (trailing slashes removed).
     *
     * @param baseUrl the prefix
     * @return the full endpoint
     */
    public static URI endpoint(URI baseUrl) {
        return StreamingCall.endpoint(baseUrl, MESSAGES_PATH);
    }

    @Override
    public String id() {
        return PROVIDER_ID;
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
        AnthropicMessagesCodec.StreamDecoder d = codec.newDecoder(request.model());
        Map<String, String> headers = Map.of("x-api-key", apiKey, "anthropic-version", API_VERSION);
        StreamingCall.Request req = new StreamingCall.Request(request, endpoint, headers, codec.toRequestBody(request),
            StreamingCall.decoder(d::feed, d::finish, d::fail));
        return call.run(req, sink, cancel);
    }
}
