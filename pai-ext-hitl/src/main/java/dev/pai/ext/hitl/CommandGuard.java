/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.ContentBlock;

/**
 * Refuses commands that match a rule of {@value #RULES}: one regular expression per line (matched anywhere, ignoring
 * case), each preceded by a {@code #} line naming the rule. A safety net for the obvious cases, not a sandbox.
 */
final class CommandGuard {

    /** Class path resource holding the rules. */
    static final String RULES = "command-guard.txt";
    /** Argument name the guard inspects. */
    static final String COMMAND_ARGUMENT = "command";

    private final List<Rule> rules;

    private CommandGuard(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * One rule.
     *
     * @param id rule id from the comment line above it
     * @param pattern the expression
     */
    record Rule(String id, Pattern pattern) {
    }

    /**
     * Loads the built-in rules.
     *
     * @return the guard
     */
    static CommandGuard builtin() {
        try (InputStream in = Objects.requireNonNull(CommandGuard.class.getResourceAsStream(RULES), RULES)) {
            return new CommandGuard(
                parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RULES, e);
        }
    }

    static List<Rule> parse(List<String> lines) {
        List<Rule> out = new ArrayList<>();
        String id = "";
        for (String line : lines) {
            String t = line.strip();
            if (t.startsWith("#")) {
                id = t.substring(1).strip();
            } else if (!t.isEmpty()) {
                out.add(new Rule(id, Pattern.compile(t, Pattern.CASE_INSENSITIVE)));
            }
        }
        return out;
    }

    /**
     * Returns the rules.
     *
     * @return rules in file order
     */
    List<Rule> rules() {
        return rules;
    }

    /**
     * Checks the text of a call's {@value #COMMAND_ARGUMENT} argument, whatever the tool.
     *
     * @param call the call
     * @return the id of the first matching rule, empty when none matches
     */
    Optional<String> check(ContentBlock.ToolCall call) {
        JsonNode command = call.arguments().path(COMMAND_ARGUMENT);
        return command.isTextual() ? match(command.asText()) : Optional.empty();
    }

    Optional<String> match(String command) {
        return rules.stream().filter(r -> r.pattern().matcher(command).find()).map(Rule::id).findFirst();
    }
}
