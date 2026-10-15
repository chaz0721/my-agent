/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.model.ModelRef;
import dev.pai.model.preset.ProviderPresets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSetupTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private ModelCatalog catalog() {
        return ModelCatalog.load(home.resolve(ModelCatalog.FILE_NAME), ProviderPresets.builtin());
    }

    private ModelCatalog.Entry entry(String ref) {
        return catalog().find(ref).orElseThrow();
    }

    @Test
    void catalog_lists_every_preset_model_and_user_models_replace_them() throws IOException {
        assertThat(catalog().entries()).extracting(ModelCatalog.Entry::ref).contains("deepseek/deepseek-chat",
            "glm/glm-5.1", "step/step-3.5-flash", "kimi/kimi-k2.6", "freellmapi/auto", "xfyun/Qwen3.6-35B-A3B",
            "agnes/agnes-2.0-flash");
        Files.writeString(home.resolve(ModelCatalog.FILE_NAME),
            "[{\"providerId\":\"deepseek\",\"modelId\":\"deepseek-chat\",\"contextWindow\":64000}]");
        ModelCatalog.Entry e = entry("deepseek/deepseek-chat");
        assertThat(e.model().contextWindow()).isEqualTo(64000);
        assertThat(e.apiKeyEnv()).containsExactly("DEEPSEEK_API_KEY", "PAI_OPENAI_COMPAT_API_KEY");
        assertThat(e.flags().forceHttp1()).isTrue();
        assertThat(e.presetBase()).isTrue();
    }

    @Test
    void deepseek_takes_its_own_key_or_the_shared_openai_compat_key() {
        EnvConfig own =
            EnvConfig.load(Map.of("DEEPSEEK_API_KEY", "k-ds", "PAI_OPENAI_COMPAT_API_KEY", "k-oc"), cwd, home, true);
        assertThat(ProviderSetup.credentials(entry("deepseek/deepseek-chat"), own).apiKey()).isEqualTo("k-ds");
        EnvConfig shared = EnvConfig.load(
            Map.of("PAI_OPENAI_COMPAT_API_KEY", "k-oc", "PAI_OPENAI_COMPAT_BASE_URL", "https://api.deepseek.com"), cwd,
            home, true);
        EnvConfig.Credentials c = ProviderSetup.credentials(entry("deepseek/deepseek-chat"), shared);
        assertThat(c.apiKey()).isEqualTo("k-oc");
        assertThat(c.baseUrl()).hasValue("https://api.deepseek.com");
        ProviderSetup.Selected s = ProviderSetup.fromEntry(entry("deepseek/deepseek-chat"), shared, false);
        assertThat(s.provider().id()).isEqualTo("deepseek");
        assertThat(s.provider()).isInstanceOf(RetryingProviderImpl.class);
        assertThat(s.model().capabilities()).contains(ModelRef.CapabilityEnum.TOOLS);
    }

    @Test
    void shared_key_meant_for_another_host_is_never_sent_to_the_preset() {
        EnvConfig other = EnvConfig.load(Map.of("PAI_OPENAI_COMPAT_API_KEY", "k-glm", "PAI_OPENAI_COMPAT_BASE_URL",
            "https://open.bigmodel.cn/api/paas/v4"), cwd, home, true);
        assertThatThrownBy(() -> ProviderSetup.credentials(entry("deepseek/deepseek-chat"), other))
            .hasMessage("missing DEEPSEEK_API_KEY or PAI_OPENAI_COMPAT_API_KEY");
    }

    @Test
    void preset_base_url_comes_from_its_variable_in_the_key_layer() throws IOException {
        Files.writeString(cwd.resolve(".env"), "KIMI_BASE_URL=https://evil.example/v1\n");
        EnvConfig cfg = EnvConfig.load(Map.of("KIMI_API_KEY", "k"), cwd, home, true);
        assertThat(ProviderSetup.fromEntry(entry("kimi/kimi-k2.6"), cfg, false).model().baseUrl())
            .hasToString("https://api.moonshot.ai/v1");
        EnvConfig env =
            EnvConfig.load(Map.of("KIMI_API_KEY", "k", "KIMI_BASE_URL", "https://proxy.example/v1"), cwd, home, true);
        assertThat(ProviderSetup.fromEntry(entry("kimi/kimi-k2.6"), env, false).model().baseUrl())
            .hasToString("https://proxy.example/v1");
    }

    @Test
    void provider_option_selects_a_preset_and_xfyun_models_offer_no_tools() {
        EnvConfig cfg = EnvConfig.load(Map.of("XFYUN_MAAS_API_KEY", "k"), cwd, home, true);
        ProviderSetup.Selected s =
            ProviderSetup.select(CliOptions.parse(new String[] {"--provider", "xfyun"}), cfg, false, catalog());
        assertThat(s.model().providerId()).isEqualTo("xfyun");
        assertThat(s.model().modelId()).isEqualTo("Qwen3.6-35B-A3B");
        assertThat(s.model().capabilities()).doesNotContain(ModelRef.CapabilityEnum.TOOLS);
        assertThatThrownBy(
            () -> ProviderSetup.select(CliOptions.parse(new String[] {"--provider", "nope"}), cfg, false, catalog()))
            .hasMessage("unknown provider: nope");
    }

    @Test
    void generic_openai_compat_on_a_preset_host_gets_its_flags() {
        EnvConfig cfg = EnvConfig.load(Map.of("PAI_OPENAI_COMPAT_API_KEY", "k", "PAI_OPENAI_COMPAT_BASE_URL",
            "https://maas-api.cn-huabei-1" + ".xf-yun.com/v2"), cwd, home, true);
        ProviderSetup.Selected s = ProviderSetup.select(
            CliOptions.parse(new String[] {"--provider", "openai-compat", "--model", "q"}), cfg, false, catalog());
        assertThat(s.model().capabilities()).doesNotContain(ModelRef.CapabilityEnum.TOOLS);
    }
}
