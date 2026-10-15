/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SecretFilterTest {

    static Stream<Arguments> secrets() {
        return Stream.of(Arguments.of("my key is sk-abcdefghijklmnop1234", "API key"),
            Arguments.of("aws AKIAABCDEFGHIJKLMNOP", "API key"),
            Arguments.of("token ghp_abcdefghijklmnopqrstuvwxyz0123456789", "API key"),
            Arguments.of("Authorization: Bearer abcdefghijklmnop.qrstuv", "bearer token"),
            Arguments.of("-----BEGIN OPENSSH PRIVATE KEY-----", "private key"),
            Arguments.of("DEEPSEEK_API_KEY=abcdef123456", "credential assignment"),
            Arguments.of("password: hunter2hunter2", "credential assignment"),
            Arguments.of("card 4111 1111 1111 1111 exp 12/30", "card number"));
    }

    @ParameterizedTest
    @MethodSource("secrets")
    void refuses_secret_shapes(String text, String kind) {
        assertThat(SecretFilter.detect(text)).contains(kind);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prefers tabs over spaces", "the API key lives in the DEEPSEEK_API_KEY variable",
        "order 1234 5678 9012 3456 shipped", "call me at 2026-10-15", "the password policy needs 12 chars"})
    void lets_ordinary_text_through(String text) {
        assertThat(SecretFilter.detect(text)).isEmpty();
    }

    @Test
    void luhn() {
        assertThat(SecretFilter.luhn("4111111111111111")).isTrue();
        assertThat(SecretFilter.luhn("4111111111111112")).isFalse();
    }
}
