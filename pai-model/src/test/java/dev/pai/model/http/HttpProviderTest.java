/* pai-next — Apache-2.0 */
package dev.pai.model.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.CancelTokenSource;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.Fixtures;
import dev.pai.model.Json;
import dev.pai.model.ModelRef;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.StreamEvent;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class HttpProviderTest {

    private static final String KEY = "test-key-not-secret";

    private HttpServer server;
    private final Map<String, String> seenHeaders = new ConcurrentHashMap<>();
    private final List<String> seenBodies = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok/chat/completions", ex -> serveFixture(ex, "/fixtures/openai-compat/text.sse"));
        server.createContext("/ok/v1/messages", ex -> serveFixture(ex, "/fixtures/anthropic/tool-use.sse"));
        server.createContext("/limited/chat/completions", ex -> respond(ex, 429, "{\"error\":\"slow down\"}"));
        server.createContext("/slow/chat/completions", this::serveSlowly);
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private URI base(String prefix) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + prefix + "/");
    }

    private static ChatRequest request(String provider) {
        ModelRef model =
            new ModelRef(provider, "m", URI.create("http://unused.invalid"), 1000, 100, java.util.Set.of());
        return new ChatRequest(model, Optional.of("sys"), List.of(UserMessage.text("hi")), List.of(),
            ChatOptions.defaults());
    }

    @Test
    void openai_compat_endpoint_is_prefix_plus_chat_completions() {
        assertThat(OpenAiCompatProviderImpl.endpoint(URI.create("https://api.deepseek.com")))
            .hasToString("https://api.deepseek.com/chat/completions");
        assertThat(OpenAiCompatProviderImpl.endpoint(URI.create("https://api.deepseek.com/v1/")))
            .hasToString("https://api.deepseek.com/v1/chat/completions");
        assertThat(OpenAiCompatProviderImpl.endpoint(URI.create("https://open.bigmodel.cn/api/paas/v4")))
            .hasToString("https://open.bigmodel.cn/api/paas/v4/chat/completions");
    }

    @Test
    void anthropic_endpoint_is_prefix_plus_v1_messages() {
        assertThat(AnthropicProviderImpl.endpoint(URI.create(AnthropicProviderImpl.DEFAULT_BASE_URL)))
            .hasToString("https://api.anthropic.com/v1/messages");
        assertThat(AnthropicProviderImpl.endpoint(URI.create("https://gateway.example/anthropic//")))
            .hasToString("https://gateway.example/anthropic/v1/messages");
    }

    @Test
    void openai_compat_streams_fixture_with_bearer_auth() throws IOException {
        OpenAiCompatProviderImpl p = new OpenAiCompatProviderImpl("deepseek", KEY, base("ok"), new SseClient(),
            new OpenAiCompatProviderImpl.Flags(true, true));
        List<StreamEvent> events = new ArrayList<>();
        AssistantMessage m = p.stream(request("deepseek"), events::add, CancelToken.none());
        assertThat(m.text()).isEqualTo("Hello from the fixture.");
        assertThat(m.providerId()).isEqualTo("deepseek");
        assertThat(m.modelId()).isEqualTo("m");
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Start.class);
        assertThat(events.get(events.size() - 1)).isEqualTo(new StreamEvent.Done(m));
        assertThat(seenHeaders.get("authorization")).isEqualTo("Bearer " + KEY);
        JsonNode body = Json.mapper().readTree(seenBodies.get(0));
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
    }

    @Test
    void anthropic_sends_key_and_version_headers() {
        AnthropicProviderImpl p = new AnthropicProviderImpl(KEY, base("ok"), new SseClient());
        AssistantMessage m = p.stream(request("anthropic"), e -> {
        }, CancelToken.none());
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.TOOL_USE);
        assertThat(seenHeaders.get("x-api-key")).isEqualTo(KEY);
        assertThat(seenHeaders.get("anthropic-version")).isEqualTo(AnthropicProviderImpl.API_VERSION);
    }

    @Test
    void non_2xx_becomes_error_event_with_status_and_body() {
        OpenAiCompatProviderImpl p = new OpenAiCompatProviderImpl("x", KEY, base("limited"), new SseClient(),
            OpenAiCompatProviderImpl.Flags.NONE);
        List<StreamEvent> events = new ArrayList<>();
        AssistantMessage m = p.stream(request("x"), events::add, CancelToken.none());
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.ERROR);
        StreamEvent.Error err = (StreamEvent.Error)events.get(events.size() - 1);
        assertThat(err.message()).isEqualTo("HTTP 429: {\"error\":\"slow down\"}");
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Start.class);
    }

    @Test
    void connection_failure_becomes_error_event() {
        OpenAiCompatProviderImpl p = new OpenAiCompatProviderImpl("x", KEY, URI.create("http://127.0.0.1:1"),
            new SseClient(Duration.ofSeconds(2L), Duration.ofSeconds(2L), false, false),
            OpenAiCompatProviderImpl.Flags.NONE);
        AssistantMessage m = p.stream(request("x"), e -> {
        }, CancelToken.none());
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.ERROR);
        assertThat(m.errorMessage()).hasValueSatisfying(s -> assertThat(s).startsWith("transport failure"));
    }

    @Test
    @Timeout(20)
    void cancel_mid_stream_aborts_with_partial() {
        OpenAiCompatProviderImpl p =
            new OpenAiCompatProviderImpl("x", KEY, base("slow"), new SseClient(), OpenAiCompatProviderImpl.Flags.NONE);
        CancelTokenSource cancel = new CancelTokenSource();
        List<StreamEvent> events = new ArrayList<>();
        AssistantMessage m = p.stream(request("x"), e -> {
            events.add(e);
            if (e instanceof StreamEvent.TextDelta) {
                cancel.cancel();
            }
        }, cancel);
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.ABORTED);
        assertThat(m.text()).isEqualTo("first");
        assertThat(events.get(events.size() - 1)).isInstanceOf(StreamEvent.Error.class);
    }

    @Test
    void already_cancelled_token_never_connects() {
        CancelTokenSource cancel = new CancelTokenSource();
        cancel.cancel();
        OpenAiCompatProviderImpl p =
            new OpenAiCompatProviderImpl("x", KEY, base("ok"), new SseClient(), OpenAiCompatProviderImpl.Flags.NONE);
        AssistantMessage m = p.stream(request("x"), e -> {
        }, cancel);
        assertThat(m.stopReason()).isEqualTo(StopReasonEnum.ABORTED);
        assertThat(seenBodies).isEmpty();
    }

    private void record(HttpExchange ex) throws IOException {
        ex.getRequestHeaders().forEach((k, v) -> seenHeaders.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
        synchronized (seenBodies) {
            seenBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void serveFixture(HttpExchange ex, String fixture) throws IOException {
        record(ex);
        respond(ex, 200, Fixtures.text(fixture));
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", status == 200 ? "text/event-stream" : "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void serveSlowly(HttpExchange ex) throws IOException {
        record(ex);
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            release.await(10L, TimeUnit.SECONDS);
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"late\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // client went away after cancelling
        }
    }
}
