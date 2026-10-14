/* pai-next — Apache-2.0 */
package dev.pai.core.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptBuilderTest {

    /** Rough upper bound: English text averages well under 1.4 tokens per word. */
    private static final double TOKENS_PER_WORD = 1.4d;
    private static final int MAX_BASE_TOKENS = 900;

    @Test
    void builds_base_then_sections() {
        String p =
            new SystemPromptBuilder().base("Base.\n").section("Environment", "cwd: /x\n").section("Extra", "y").build();
        assertThat(p).isEqualTo("Base.\n\n# Environment\n\ncwd: /x\n\n# Extra\n\ny");
    }

    @Test
    void default_base_is_short_english() {
        String base = SystemPromptBuilder.defaultBase();
        int words = base.split("\\s+").length;
        assertThat(words * TOKENS_PER_WORD).isLessThan(MAX_BASE_TOKENS);
        assertThat(base).contains("read", "edit", "write", "bash");
        assertThat(base.chars().allMatch(c -> c < 128)).isTrue();
    }
}
