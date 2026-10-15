/* pai-next — Apache-2.0 */
package dev.pai.model.preset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.ChatRequest;
import dev.pai.model.Json;
import dev.pai.model.ModelRef;
import dev.pai.model.codec.RequestHistory;
import dev.pai.model.codec.openai.OpenAiCompletionsCodec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden request body per preset, encoded as the loop would send it: tools only when the model has the tools
 * capability, reasoning echoed only when the preset says so. A missing golden file is written (under
 * {@code src/test/resources/golden/presets}) and the test fails, so new goldens are reviewed before they count.
 */
class PresetRequestBodyTest {

    private static final String DIR = "/golden/presets/";

    static java.util.List<ProviderPreset> presets() {
        return ProviderPresets.builtin();
    }

    @ParameterizedTest
    @MethodSource("presets")
    void request_body_matches_golden(ProviderPreset preset) throws IOException {
        ModelRef model = preset.models().get(0);
        ChatRequest full = RequestHistory.request(model, "reasoning_content");
        ChatRequest sent = model.capabilities().contains(ModelRef.CapabilityEnum.TOOLS) ? full
            : new ChatRequest(full.model(), full.systemPrompt(), full.messages(), java.util.List.of(), full.options());
        ObjectNode body = new OpenAiCompletionsCodec(preset.id(), preset.flags().echoReasoning()).toRequestBody(sent);
        String name = preset.id() + ".json";
        try (InputStream in = PresetRequestBodyTest.class.getResourceAsStream(DIR + name)) {
            if (in == null) {
                Path out = Path.of("src/test/resources/golden/presets").resolve(name);
                Files.createDirectories(out.getParent());
                Files.writeString(out, Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(body) + "\n",
                    StandardCharsets.UTF_8);
                throw new AssertionError("golden written to " + out.toAbsolutePath() + "; review and rerun");
            }
            JsonNode golden = Json.mapper().readTree(in);
            assertThat(body).isEqualTo(golden);
        }
    }

    @ParameterizedTest
    @MethodSource("presets")
    void tools_and_reasoning_follow_the_flags(ProviderPreset preset) {
        ModelRef model = preset.models().get(0);
        ChatRequest full = RequestHistory.request(model, "reasoning_content");
        boolean tools = model.capabilities().contains(ModelRef.CapabilityEnum.TOOLS);
        ChatRequest sent = tools ? full
            : new ChatRequest(full.model(), full.systemPrompt(), full.messages(), java.util.List.of(), full.options());
        ObjectNode body = new OpenAiCompletionsCodec(preset.id(), preset.flags().echoReasoning()).toRequestBody(sent);
        assertThat(body.has("tools")).isEqualTo(!"xfyun".equals(preset.id()));
        JsonNode assistant = body.path("messages").path(2);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        boolean echoes = "deepseek".equals(preset.id()) || "kimi".equals(preset.id());
        assertThat(assistant.has("reasoning_content")).isEqualTo(echoes);
    }
}
