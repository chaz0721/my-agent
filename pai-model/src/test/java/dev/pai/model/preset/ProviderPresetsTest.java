/* pai-next — Apache-2.0 */
package dev.pai.model.preset;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.model.ModelRef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderPresetsTest {

    @TempDir
    Path dir;

    @Test
    void builtin_table_covers_the_seven_providers() {
        List<ProviderPreset> all = ProviderPresets.builtin();
        assertThat(all).extracting(ProviderPreset::id).containsExactly("glm", "deepseek", "step", "kimi", "freellmapi",
            "xfyun", "agnes");
        assertThat(all).allSatisfy(p -> {
            assertThat(p.api()).isEqualTo(ProviderPreset.OPENAI_COMPLETIONS);
            assertThat(p.apiKeyEnv()).isNotEmpty();
            assertThat(p.models()).isNotEmpty().allSatisfy(m -> {
                assertThat(m.providerId()).isEqualTo(p.id());
                assertThat(m.baseUrl()).isEqualTo(p.baseUrl());
            });
        });
    }

    @Test
    void flags_drive_tool_and_image_capabilities() {
        List<ProviderPreset> all = ProviderPresets.builtin();
        ProviderPreset xfyun = ProviderPresets.find(all, "xfyun").orElseThrow();
        assertThat(xfyun.flags().sendTools()).isFalse();
        assertThat(xfyun.models())
            .allSatisfy(m -> assertThat(m.capabilities()).doesNotContain(ModelRef.CapabilityEnum.TOOLS));
        ProviderPreset deepseek = ProviderPresets.find(all, "deepseek").orElseThrow();
        assertThat(deepseek.flags().forceHttp1()).isTrue();
        assertThat(deepseek.flags().echoReasoning()).isTrue();
        assertThat(deepseek.apiKeyEnv()).containsExactly("DEEPSEEK_API_KEY", "PAI_OPENAI_COMPAT_API_KEY");
        assertThat(deepseek.model("deepseek-chat").orElseThrow().capabilities()).contains(ModelRef.CapabilityEnum.TOOLS)
            .doesNotContain(ModelRef.CapabilityEnum.IMAGE_INPUT);
        assertThat(deepseek.model("deepseek-reasoner").orElseThrow().capabilities())
            .contains(ModelRef.CapabilityEnum.THINKING);
        assertThat(ProviderPresets.find(all, "kimi").orElseThrow().flags().echoReasoning()).isTrue();
        assertThat(ProviderPresets.find(all, "glm").orElseThrow().flags().echoReasoning()).isFalse();
    }

    @Test
    void user_file_overrides_fields_merges_flags_and_models_and_appends_new_providers() throws IOException {
        Path file = dir.resolve("providers.json");
        Files.writeString(file, """
            [{"id":"xfyun","flags":{"extraHeaders":{"lora_id":"res-1"}},
              "models":[{"id":"my-tuned","contextWindow":32000,"capabilities":["thinking"]}]},
             {"id":"intranet","baseUrl":"https://llm.corp.example/v1","apiKeyEnv":"CORP_KEY",
              "models":[{"id":"corp-1"}]}]
            """, StandardCharsets.UTF_8);
        List<ProviderPreset> merged = ProviderPresets.merge(ProviderPresets.builtin(), file);
        ProviderPreset xfyun = ProviderPresets.find(merged, "xfyun").orElseThrow();
        assertThat(xfyun.flags().extraHeaders()).containsEntry("lora_id", "res-1");
        assertThat(xfyun.flags().sendTools()).isFalse();
        assertThat(xfyun.models()).extracting(ModelRef::modelId).containsExactly("Qwen3.6-35B-A3B", "my-tuned");
        assertThat(xfyun.model("my-tuned").orElseThrow().capabilities())
            .containsExactly(ModelRef.CapabilityEnum.THINKING);
        ProviderPreset corp = merged.get(merged.size() - 1);
        assertThat(corp.id()).isEqualTo("intranet");
        assertThat(corp.apiKeyEnv()).containsExactly("CORP_KEY");
        assertThat(corp.flags()).isEqualTo(ProviderFlags.NONE);
        assertThat(corp.models().get(0).capabilities()).containsExactly(ModelRef.CapabilityEnum.TOOLS);
        assertThat(ProviderPresets.merge(merged, dir.resolve("missing.json"))).isSameAs(merged);
    }

    @Test
    void invalid_user_file_is_a_readable_error() throws IOException {
        Path file = dir.resolve("providers.json");
        Files.writeString(file, "[{\"id\":\"new\"}]", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ProviderPresets.merge(List.of(), file)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs a baseUrl");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ProviderPresets.merge(List.of(), file)).hasMessageContaining("JSON array");
    }

    @Test
    void rebasing_moves_every_model() {
        ProviderPreset kimi = ProviderPresets.find(ProviderPresets.builtin(), "kimi").orElseThrow()
            .withBaseUrl(URI.create("https://proxy.example/v1"));
        assertThat(kimi.models()).allSatisfy(m -> assertThat(m.baseUrl()).hasToString("https://proxy.example/v1"));
    }
}
