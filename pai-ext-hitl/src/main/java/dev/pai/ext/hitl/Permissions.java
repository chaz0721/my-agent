/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.pai.model.ContentBlock;
import dev.pai.model.Json;

/**
 * Allow and deny rules from {@code ~/.pai/ext/hitl/}{@value #FILE_NAME} and, for a trusted project, from
 * {@code <cwd>/.pai/}{@value #FILE_NAME}: {@code {"allow":[{"tool":"bash","pattern":"mvn *"},{"tool":"edit"}],
 * "deny":[{"tool":"bash","pattern":"git push*"}]}}. A rule without pattern covers every call of the tool; a pattern is
 * a glob ({@code *} any text, {@code ?} one character; ignoring case on Windows) over the call's subject: the
 * {@code bash} command, else the {@code path} argument (for allow rules normalized and resolved; one outside the
 * working directory matches no allow pattern), else the arguments as JSON. A deny rule matches a command when it
 * matches the whole line, any part between {@code ; & | ( )}, backticks, {@code $} and newlines, or, word by word, any
 * simple command as {@link CommandWords} sees it ({@code env git -C . push} is denied by {@code git push*}: the pattern
 * words must appear in order, the first one as a program name). A line that hides what it runs is {@link #uncertain}
 * while bash deny rules exist. An allow rule never matches a command that contains separators, redirections or quotes.
 * Deny rules win, so project rules can add denials but cannot lift the user's; only the user's file is changed, through
 * a temporary file and an atomic move.
 */
final class Permissions {

    static final String FILE_NAME = "permissions.json";
    private static final Pattern SEGMENTS = Pattern.compile("[;&|()`$\\n\\r]+");
    private static final Pattern COMPOUND = Pattern.compile("[;&|()`$<>'\"\\\\\\n\\r]");
    /** Whether pai runs on Windows: patterns ignore case and {@code cmd} may run the command line. */
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private final Optional<Path> userFile;
    private final List<Rule> user = new ArrayList<>();
    private final List<Rule> project = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final boolean windows;
    private final Path cwd;

    /**
     * One rule.
     *
     * @param allow whether it allows (else denies)
     * @param tool the tool name
     * @param pattern the glob over the subject, empty for every call
     * @param projectLevel whether it comes from the project's file
     */
    record Rule(boolean allow, String tool, Optional<String> pattern, boolean projectLevel) {

        /** The rule as {@code /approvals list} shows it, control characters made visible. */
        String describe() {
            return CallPreview.visible((allow ? "allow " : "deny ") + tool + pattern.map(p -> " " + p).orElse("")
                + (projectLevel ? " (project)" : ""));
        }
    }

    Permissions(Optional<Path> userFile, Optional<Path> projectFile, boolean windows, Path cwd) {
        this.userFile = userFile;
        this.windows = windows;
        this.cwd = cwd.toAbsolutePath().normalize();
        userFile.ifPresent(f -> read(f, false, user));
        projectFile.ifPresent(f -> read(f, true, project));
    }

    /** Loads the user's rules from {@code configDir} and, when given, the trusted project's rules. */
    static Permissions load(Path configDir, Optional<Path> projectFile, Path cwd) {
        return new Permissions(Optional.of(configDir.resolve(FILE_NAME)), projectFile, WINDOWS, cwd);
    }

    /** No rules and nowhere to save them. */
    static Permissions none() {
        return new Permissions(Optional.empty(), Optional.empty(), WINDOWS, Path.of("."));
    }

    /** The user's file, where added rules go. */
    Optional<Path> userFile() {
        return userFile;
    }

    List<String> problems() {
        return List.copyOf(problems);
    }

    /** All rules, user rules first, as numbered by {@code /approvals forget}; an unmodifiable snapshot. */
    synchronized List<Rule> rules() {
        List<Rule> all = new ArrayList<>(user);
        all.addAll(project);
        return List.copyOf(all);
    }

    /** Whether a rule without pattern denies the tool, so that it is not even offered. */
    boolean deniesTool(String tool) {
        return rules().stream().anyMatch(r -> !r.allow() && r.tool().equals(tool) && r.pattern().isEmpty());
    }

    /** The first deny rule matching the call. */
    Optional<Rule> denying(ContentBlock.ToolCall call) {
        String subject = subject(call);
        boolean command = CallPreview.BASH_TOOL.equals(call.name());
        Optional<String> path = normalizedPath(call);
        return rules().stream().filter(r -> !r.allow() && r.tool().equals(call.name()))
            .filter(r -> r.pattern().isEmpty() || deniedText(r.pattern().get(), subject)
                || command && deniedWords(r.pattern().get(), subject)
                || path.map(n -> glob(r.pattern().get()).matcher(n).matches()).orElse(false))
            .findFirst();
    }

    /**
     * Whether a {@code bash} call must be asked about although no deny rule matched it: bash deny rules exist and the
     * line hides what it runs ({@link CommandWords#opaque}), so that neither a mode, an "always" answer nor an allow
     * rule may let it through.
     */
    boolean uncertain(ContentBlock.ToolCall call) {
        JsonNode command = call.arguments().path(CommandGuard.COMMAND_ARGUMENT);
        return CallPreview.BASH_TOOL.equals(call.name()) && command.isTextual()
            && rules().stream().anyMatch(r -> !r.allow() && CallPreview.BASH_TOOL.equals(r.tool()))
            && CommandWords.opaque(command.asText(), windows);
    }

    /** Whether an allow rule matches the call. */
    boolean allows(ContentBlock.ToolCall call) {
        String subject = subject(call);
        boolean command = CallPreview.BASH_TOOL.equals(call.name());
        boolean hasPath = call.arguments().path(PathGuard.PATH_ARGUMENT).isTextual();
        Optional<String> target = command || !hasPath ? Optional.of(subject) : normalizedPath(call);
        return rules().stream().filter(r -> r.allow() && r.tool().equals(call.name()))
            .anyMatch(r -> r.pattern().isEmpty() || target.isPresent() && !(command && compound(subject))
                && glob(r.pattern().get()).matcher(target.get()).matches());
    }

    /** The {@code path} argument relative to the working directory, links resolved; empty when outside or none. */
    private Optional<String> normalizedPath(ContentBlock.ToolCall call) {
        JsonNode path = call.arguments().path(PathGuard.PATH_ARGUMENT);
        if (!path.isTextual()) {
            return Optional.empty();
        }
        try {
            Optional<Path> root = PathGuard.real(cwd);
            Optional<Path> real = PathGuard.real(cwd.resolve(path.asText()).toAbsolutePath().normalize());
            return root.isPresent() && real.isPresent() && real.get().startsWith(root.get())
                ? Optional.of(root.get().relativize(real.get()).toString().replace('\\', '/')) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * The pattern's words appear in a simple command in order, the first one as a program name anywhere in it (so that
     * wrappers with arguments, such as {@code sudo -u x git push}, are seen through; denying too much is the safe
     * side).
     */
    private boolean deniedWords(String pattern, String command) {
        List<String> p = List.of(pattern.strip().split("\\s+"));
        Pattern first = glob(p.get(0));
        return CommandWords.commands(command, windows).map(cs -> cs.stream().anyMatch(words -> {
            for (int start = 0; start < words.size(); start++) {
                if (first.matcher(CommandWords.program(words.get(start))).matches()
                    && inOrder(p.subList(1, p.size()), words.subList(start + 1, words.size()))) {
                    return true;
                }
            }
            return false;
        })).orElse(false);
    }

    private boolean inOrder(List<String> parts, List<String> words) {
        int j = 0;
        for (String part : parts) {
            Pattern g = glob(part);
            while (j < words.size() && !g.matcher(words.get(j)).matches()) {
                j++;
            }
            if (j++ >= words.size()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Adds a rule to the user's file.
     *
     * @param allow whether it allows
     * @param tool the tool name
     * @param pattern the glob, empty for every call
     * @throws IOException when the file cannot be written
     * @throws IllegalArgumentException when the tool or pattern holds control or invisible characters
     */
    synchronized void add(boolean allow, String tool, Optional<String> pattern) throws IOException {
        if (!printable(tool) || tool.isBlank() || pattern.map(p -> !printable(p) || p.isBlank()).orElse(false)) {
            throw new IllegalArgumentException("rules may not hold control or invisible characters");
        }
        user.add(new Rule(allow, tool, pattern, false));
        save();
    }

    /**
     * Removes the n-th rule (1-based) of {@link #rules()}; only the user's rules can be removed.
     *
     * @param n the number shown by {@code /approvals list}
     * @return the removed rule, empty when there is no such user rule
     * @throws IOException when the file cannot be written
     */
    synchronized Optional<Rule> forget(int n) throws IOException {
        if (n < 1 || n > user.size()) {
            return Optional.empty();
        }
        Rule r = user.remove(n - 1);
        save();
        return Optional.of(r);
    }

    /** Whether an allow pattern can never match the command: it joins commands, redirects, substitutes or quotes. */
    static boolean compound(String command) {
        return COMPOUND.matcher(command).find();
    }

    /** Whether the text shows as it is: no control, direction or zero-width characters. */
    static boolean printable(String text) {
        return CallPreview.visible(text).equals(text) && text.indexOf('\n') < 0 && text.indexOf('\t') < 0;
    }

    private boolean deniedText(String pattern, String subject) {
        Pattern p = glob(pattern);
        if (p.matcher(subject.strip()).matches()) {
            return true;
        }
        return SEGMENTS.splitAsStream(subject).map(String::strip).anyMatch(s -> p.matcher(s).matches());
    }

    private Pattern glob(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            sb.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString(),
            Pattern.DOTALL | (windows ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0));
    }

    private static String subject(ContentBlock.ToolCall call) {
        JsonNode command = call.arguments().path(CommandGuard.COMMAND_ARGUMENT);
        if (CallPreview.BASH_TOOL.equals(call.name()) && command.isTextual()) {
            return command.asText();
        }
        JsonNode path = call.arguments().path(PathGuard.PATH_ARGUMENT);
        return path.isTextual() ? path.asText() : call.arguments().toString();
    }

    private void read(Path file, boolean projectLevel, List<Rule> into) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = Json.mapper().readTree(file.toFile());
            for (String kind : List.of("allow", "deny")) {
                for (JsonNode r : root.path(kind)) {
                    String tool = r.path("tool").asText("");
                    Optional<String> pattern =
                        r.path("pattern").isTextual() ? Optional.of(r.get("pattern").asText()) : Optional.empty();
                    if (tool.isBlank() || !printable(tool) || pattern.map(p -> !printable(p)).orElse(false)) {
                        problems.add("ignoring a rule in " + file + " with an empty tool or control characters");
                    } else {
                        into.add(new Rule("allow".equals(kind), tool, pattern, projectLevel));
                    }
                }
            }
        } catch (IOException e) {
            problems.add("cannot read " + file + ": " + e.getMessage());
        }
    }

    private void save() throws IOException {
        if (userFile.isEmpty()) {
            throw new IOException("no permissions file");
        }
        Path file = userFile.get();
        ObjectNode root = Json.mapper().createObjectNode();
        ArrayNode allow = root.putArray("allow");
        ArrayNode deny = root.putArray("deny");
        for (Rule r : user) {
            ObjectNode o = (r.allow() ? allow : deny).addObject().put("tool", r.tool());
            r.pattern().ifPresent(p -> o.put("pattern", p));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(FILE_NAME + "." + ProcessHandle.current().pid() + "." + System.nanoTime());
        Files.writeString(tmp, Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
            StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
