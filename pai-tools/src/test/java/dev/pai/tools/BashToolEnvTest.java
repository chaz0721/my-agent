/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commands do not see the provider keys pai reads, so an accidental {@code env} cannot print them into the transcript.
 */
class BashToolEnvTest {

    @Test
    void provider_keys_are_removed_and_everything_else_is_kept() {
        Map<String,
            String> env = new HashMap<>(Map.of("PAI_OPENAI_COMPAT_API_KEY", "k1", "PAI_ANTHROPIC_API_KEY", "k2",
                "DEEPSEEK_API_KEY", "k3", "pai_glm_api_key", "k4", "PAI_OPENAI_COMPAT_BASE_URL", "https://x",
                "MY_TOOL_API_KEY", "mine", "PATH", "/bin"));
        BashTool.withoutProviderKeys(env, Set.of());
        assertThat(env).containsOnlyKeys("PAI_OPENAI_COMPAT_BASE_URL", "MY_TOOL_API_KEY", "PATH");
    }

    @Test
    void custom_api_key_env_names_from_providers_and_models_files_are_removed(@TempDir Path home) throws IOException {
        Files.writeString(home.resolve("providers.json"),
            "[{\"id\":\"corp\",\"baseUrl\":\"https://llm.corp\",\"apiKeyEnv\":[\"CORP_LLM_TOKEN\",\"corp_backup\"]}]");
        Files.writeString(home.resolve("models.json"),
            "[{\"providerId\":\"openai-compat\",\"modelId\":\"m\",\"apiKeyEnv\":\"MY_MODEL_SECRET\"}]");
        Set<String> names = BashTool.configuredKeyNames(home);
        assertThat(names).containsExactlyInAnyOrder("CORP_LLM_TOKEN", "CORP_BACKUP", "MY_MODEL_SECRET");
        Map<String, String> env =
            new HashMap<>(Map.of("CORP_LLM_TOKEN", "a", "corp_backup", "b", "MY_MODEL_SECRET", "c", "PATH", "/bin"));
        BashTool.withoutProviderKeys(env, names);
        assertThat(env).containsOnlyKeys("PATH");
        assertThat(BashTool.configuredKeyNames(home.resolve("missing"))).isEmpty();
    }
}
