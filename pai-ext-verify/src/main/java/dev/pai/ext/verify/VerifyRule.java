/* pai-next — Apache-2.0 */
package dev.pai.ext.verify;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One verification rule: after a call of a tool named by {@code on} (a regular expression, for example
 * {@code write|edit}) whose {@code path} matches {@code pathGlob}, {@code run} is executed in the working directory.
 *
 * @param on tool names, matched as a whole
 * @param pathGlob glob over the path relative to the working directory, {@code /}-separated ({@code **}, {@code *},
 *            {@code ?}, {@code {a,b}})
 * @param run the shell command
 * @param timeoutSeconds limit for the command
 * @param replace whether the verification output replaces the tool output instead of being appended to it
 */
record VerifyRule(Pattern on, String pathGlob, String run, int timeoutSeconds, boolean replace) {

    static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /**
     * Reads a rule.
     *
     * @param n {@code {"on","pathGlob","run","timeoutSeconds","mode"}}
     * @return the rule
     * @throws IllegalArgumentException when {@code run} is missing, {@code on} is not a valid expression or the mode is
     *             neither {@code append} nor {@code replace}
     */
    static VerifyRule parse(JsonNode n) {
        String run = n.path("run").asText("").strip();
        if (run.isEmpty()) {
            throw new IllegalArgumentException("rule without run");
        }
        String mode = n.path("mode").asText("append");
        if (!"append".equals(mode) && !"replace".equals(mode)) {
            throw new IllegalArgumentException("mode must be append or replace: " + mode);
        }
        Pattern on = Pattern.compile(n.path("on").asText("write|edit"));
        int timeout = Math.max(1, n.path("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS));
        return new VerifyRule(on, n.path("pathGlob").asText("**"), run, timeout, "replace".equals(mode));
    }

    /** Parses a JSON array of rules, all or nothing. */
    static List<VerifyRule> parseAll(JsonNode array) {
        if (!array.isArray()) {
            throw new IllegalArgumentException("rules must be a JSON array");
        }
        List<VerifyRule> out = new ArrayList<>();
        array.forEach(n -> out.add(parse(n)));
        return List.copyOf(out);
    }

    /**
     * Tells whether a tool call is covered.
     *
     * @param tool the tool name
     * @param cwd the working directory, absolute and normalized
     * @param path the {@code path} argument of the call
     * @return true when the tool and the path match; paths outside the working directory never match
     */
    boolean matches(String tool, Path cwd, String path) {
        if (!on.matcher(tool).matches()) {
            return false;
        }
        Path target = cwd.resolve(path).toAbsolutePath().normalize();
        if (!target.startsWith(cwd)) {
            return false;
        }
        String rel = cwd.relativize(target).toString().replace('\\', '/');
        return glob(pathGlob).matcher(rel).matches();
    }

    /** One line for notices: {@code on write|edit **}{@code /*.java -> mvn -q compile}. */
    String describe() {
        return "on " + on.pattern() + " " + pathGlob + " -> " + run;
    }

    /** Converts a glob to a regular expression; a leading {@code **}{@code /} also matches top-level files. */
    static Pattern glob(String glob) {
        StringBuilder re = new StringBuilder();
        int depth = 0;
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (glob.startsWith("**/", i)) {
                re.append("(?:.*/)?");
                i += 3;
                continue;
            }
            if (glob.startsWith("**", i)) {
                re.append(".*");
                i += 2;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                re.append(')');
                i++;
                continue;
            }
            re.append(switch (c) {
                case '*' -> "[^/]*";
                case '?' -> "[^/]";
                case '{' -> "(?:";
                case ',' -> depth > 0 ? "|" : ",";
                default -> Pattern.quote(String.valueOf(c));
            });
            i++;
        }
        return Pattern.compile(re.toString());
    }
}
