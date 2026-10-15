/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BashOutputDecodingTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    @Test
    void lines_that_are_not_utf8_fall_back_to_the_system_encoding() {
        byte[] bytes = join("[ERROR] Calc.java:[9,21] ".getBytes(StandardCharsets.UTF_8), "需要';'\n".getBytes(GBK),
            "utf-8 line: ✓ 完成\n".getBytes(StandardCharsets.UTF_8));
        assertThat(BashTool.decode(bytes, false, StandardCharsets.UTF_8, GBK))
            .isEqualTo("[ERROR] Calc.java:[9,21] 需要';'\nutf-8 line: ✓ 完成\n");
    }

    @Test
    void without_a_different_fallback_nothing_changes() {
        byte[] bytes = "plain ✓\n".getBytes(StandardCharsets.UTF_8);
        assertThat(BashTool.decode(bytes, false, StandardCharsets.UTF_8, StandardCharsets.UTF_8))
            .isEqualTo("plain ✓\n");
        assertThat(BashTool.decode("需要".getBytes(GBK), false, GBK, GBK)).isEqualTo("需要");
    }

    @Test
    void a_cut_multibyte_character_at_the_end_is_dropped() {
        byte[] full = "ab✓".getBytes(StandardCharsets.UTF_8);
        byte[] cut = Arrays.copyOf(full, full.length - 1);
        assertThat(BashTool.completeUtf8End(cut)).isEqualTo(2);
        assertThat(BashTool.completeUtf8End(full)).isEqualTo(full.length);
        assertThat(BashTool.completeUtf8End("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(3);
        assertThat(BashTool.decode(cut, true, StandardCharsets.UTF_8, GBK)).isEqualTo("ab");
        assertThat(BashTool.decode(cut, true, StandardCharsets.UTF_8, StandardCharsets.UTF_8)).isEqualTo("ab");
    }

    /**
     * Known limit: the choice is per line, so a GBK line whose bytes happen to be valid UTF-8 is read as UTF-8.
     * {@code E4 B8 AD E6 96 87} is "中文" in UTF-8 and also valid, different, GBK text.
     */
    @Test
    void a_gbk_line_that_is_also_valid_utf8_is_read_as_utf8() {
        byte[] bytes = {(byte)0xE4, (byte)0xB8, (byte)0xAD, (byte)0xE6, (byte)0x96, (byte)0x87, '\n'};
        assertThat(BashTool.decode(bytes, false, StandardCharsets.UTF_8, GBK)).isEqualTo("中文\n")
            .isNotEqualTo(new String(bytes, GBK));
    }
}
