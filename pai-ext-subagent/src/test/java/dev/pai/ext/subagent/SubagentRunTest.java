/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentRunTest {

    @Test
    void text_is_cut_by_code_points_so_no_surrogate_pair_is_split() {
        String faces = "😀".repeat(6);
        assertThat(SubagentRun.cut(faces, 5)).isEqualTo("😀😀...");
        assertThat(SubagentRun.cut(faces, 6)).isEqualTo(faces);
        assertThat(SubagentRun.cut("abcdef", 5)).isEqualTo("ab...");
    }
}
