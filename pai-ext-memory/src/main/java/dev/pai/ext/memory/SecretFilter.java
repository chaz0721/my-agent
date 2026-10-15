/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes text that looks like it carries a credential, so that it is never written to memory: API keys of common
 * shapes, bearer tokens, private key blocks, {@code key = value} assignments of secret-like names, and card numbers (13
 * to 19 digits that pass the Luhn check).
 */
final class SecretFilter {

    /**
     * A named pattern.
     *
     * @param kind what it detects, shown to the user
     * @param pattern the expression
     */
    record Rule(String kind, Pattern pattern) {
    }

    static final List<Rule> RULES = List.of(new Rule("API key", Pattern.compile("\\b(sk|pk|rk)-[A-Za-z0-9_-]{16,}")),
        new Rule("API key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
        new Rule("API key", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}")),
        new Rule("API key", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}")),
        new Rule("API key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{30,}")),
        new Rule("bearer token", Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{16,}=*")),
        new Rule("private key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
        new Rule("credential assignment", Pattern.compile(
            "(?i)\\b[a-z0-9_]*(api[_-]?key|secret|token|password|passwd)[a-z0-9_]*\\s*[:=]\\s*['\"]?[^\\s'\"]{8,}")));

    private static final Pattern CARD = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");
    private static final int MIN_CARD_DIGITS = 13;
    private static final int LUHN_MODULUS = 10;

    private SecretFilter() {}

    /**
     * Checks a text.
     *
     * @param text the text
     * @return what kind of secret it seems to contain, empty when none
     */
    static Optional<String> detect(String text) {
        for (Rule r : RULES) {
            if (r.pattern().matcher(text).find()) {
                return Optional.of(r.kind());
            }
        }
        Matcher m = CARD.matcher(text);
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            if (digits.length() >= MIN_CARD_DIGITS && luhn(digits)) {
                return Optional.of("card number");
            }
        }
        return Optional.empty();
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                d = d > 9 ? d - 9 : d;
            }
            sum += d;
            twice = !twice;
        }
        return sum % LUHN_MODULUS == 0;
    }
}
