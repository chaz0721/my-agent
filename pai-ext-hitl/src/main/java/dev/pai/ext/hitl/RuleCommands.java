/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The rule subcommands of {@code /approvals}: {@code list}, {@code allow <tool> [pattern]}, {@code deny <tool>
 * [pattern]} and {@code forget <n>}; changes go to the user's {@link Permissions} file.
 */
final class RuleCommands {

    static final String USAGE = "usage: /approvals list | allow <tool> [pattern] | deny <tool> [pattern] | forget <n>";

    private RuleCommands() {}

    /**
     * Runs a rule subcommand.
     *
     * @param args the arguments of {@code /approvals}
     * @param permissions the rules
     * @return the output, empty when {@code args} is no rule subcommand
     */
    static Optional<String> run(String args, Permissions permissions) {
        String[] w = args.strip().split("\\s+", 3);
        try {
            return switch (w[0]) {
                case "list" -> Optional.of(list(permissions));
                case "allow", "deny" -> Optional.of(w.length < 2 ? USAGE : add(permissions, w));
                case "forget" -> Optional.of(w.length < 2 ? USAGE : forget(permissions, w[1]));
                default -> Optional.empty();
            };
        } catch (IOException | IllegalArgumentException e) {
            return Optional.of("approvals: " + CallPreview.visible(String.valueOf(e.getMessage())));
        }
    }

    private static String list(Permissions permissions) {
        List<String> lines = new ArrayList<>();
        List<Permissions.Rule> rules = permissions.rules();
        for (int i = 0; i < rules.size(); i++) {
            lines.add((i + 1) + ". " + rules.get(i).describe());
        }
        String where = permissions.userFile().map(Object::toString).orElse("no file");
        return rules.isEmpty() ? "approvals: no saved rules (" + where + ")"
            : "approvals: rules (" + where + "; deny wins):\n" + String.join("\n", lines);
    }

    private static String add(Permissions permissions, String[] w) throws IOException {
        boolean allow = "allow".equals(w[0]);
        Optional<String> pattern = w.length > 2 ? Optional.of(w[2].strip()) : Optional.empty();
        permissions.add(allow, w[1], pattern);
        return "approvals: saved \"" + new Permissions.Rule(allow, w[1], pattern, false).describe() + "\"";
    }

    private static String forget(Permissions permissions, String n) throws IOException {
        int index;
        try {
            index = Integer.parseInt(n);
        } catch (NumberFormatException e) {
            return USAGE;
        }
        return permissions.forget(index).map(r -> "approvals: forgot \"" + r.describe() + "\"")
            .orElse("approvals: no user rule " + index + " (project rules are edited in the project's file)");
    }
}
