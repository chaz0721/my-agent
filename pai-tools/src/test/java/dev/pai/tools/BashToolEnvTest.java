/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commands never see the provider keys pai reads, so the model cannot print them into the transcript.
 */
class BashToolEnvTest {

    @Test
    void provider_keys_are_removed_and_everything_else_is_kept() {
        Map<String,
            String> env = new HashMap<>(Map.of("PAI_OPENAI_COMPAT_API_KEY", "k1", "PAI_ANTHROPIC_API_KEY", "k2",
                "DEEPSEEK_API_KEY", "k3", "pai_glm_api_key", "k4", "PAI_OPENAI_COMPAT_BASE_URL", "https://x",
                "MY_TOOL_API_KEY", "mine", "PATH", "/bin"));
        BashTool.withoutProviderKeys(env);
        assertThat(env).containsOnlyKeys("PAI_OPENAI_COMPAT_BASE_URL", "MY_TOOL_API_KEY", "PATH");
    }
}
