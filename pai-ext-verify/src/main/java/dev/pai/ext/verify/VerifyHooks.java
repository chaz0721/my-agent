/* pai-next — Apache-2.0 */
package dev.pai.ext.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.AssistantMessage;
import dev.pai.model.CancelToken;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;

/**
 * Runs the matching rules after writes. Debounced per turn: when one reply writes several matching files, the command
 * runs once, after the last matching call of that reply, and its report goes into that call's result (with
 * {@code isError} unchanged: a failed check is feedback, not a failed write). It runs only if at least one matching
 * call of the turn succeeded, the user agreed for this session ({@link VerifyConsent}), and no write of the turn
 * touched a build definition ({@code pom.xml}, {@code package.json}, Gradle and Cargo files, {@code go.mod},
 * {@code .mvn/}, {@code pyproject.toml}, {@code setup.py}, {@code setup.cfg}): the model could otherwise make the check
 * run a program of its choice. Such a write keeps automatic runs off (also in later turns, each skip is reported) until
 * {@code /verify} passes or the user agrees again with {@code /verify on}.
 */
final class VerifyHooks implements AgentHooks {

    static final String PATH_ARGUMENT = "path";
    static final String SKIPPED = "verify skipped: build definition changed, run /verify to check";
    private static final List<Pattern> BUILD_DEFINITIONS =
        List.of(VerifyRule.glob("**/package.json"), VerifyRule.glob("**/pom.xml"), VerifyRule.glob("**/Cargo.toml"),
            VerifyRule.glob("**/build.rs"), VerifyRule.glob("**/go.mod"), VerifyRule.glob("**/.mvn/**"),
            VerifyRule.glob("**/settings.gradle*"), VerifyRule.glob("**/build.gradle*"),
            VerifyRule.glob("**/pyproject.toml"), VerifyRule.glob("**/setup.py"), VerifyRule.glob("**/setup.cfg"));

    private final List<VerifyRule> rules;
    private final VerifyRunner runner;
    private final Path cwd;
    private final VerifyConsent consent;
    private final Consumer<String> notifier;
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final Set<String> succeeded = ConcurrentHashMap.newKeySet();
    /** A successful write touched a build definition; automatic runs stay off until the user checks or agrees. */
    private final AtomicBoolean buildChanged = new AtomicBoolean();

    /**
     * Wiring.
     *
     * @param rules active rules
     * @param runner runs a rule
     * @param cwd working directory
     * @param consent asks before the first automatic run
     * @param notifier one-line notices to the user
     */
    record Parts(List<VerifyRule> rules, VerifyRunner runner, Path cwd, VerifyConsent consent,
        Consumer<String> notifier) {
    }

    VerifyHooks(Parts p) {
        this.rules = List.copyOf(p.rules());
        this.runner = p.runner();
        this.cwd = p.cwd().toAbsolutePath().normalize();
        this.consent = p.consent();
        this.notifier = p.notifier();
    }

    void enabled(boolean on) {
        enabled.set(on);
    }

    List<VerifyRule> rules() {
        return rules;
    }

    /** A new session: consent is asked again. */
    void sessionStarted() {
        consent.reset();
    }

    /** The user checked the build ({@code /verify} passed) or agreed again: automatic runs may resume. */
    void buildAccepted() {
        buildChanged.set(false);
    }

    boolean buildChanged() {
        return buildChanged.get();
    }

    /** Asks again (for {@code /verify on}); true when automatic runs may happen. */
    boolean reconfirm() {
        boolean yes = consent.reconfirm(commands());
        if (yes) {
            buildAccepted();
        }
        return yes;
    }

    private String commands() {
        return rules.stream().map(VerifyRule::run).collect(Collectors.joining(", "));
    }

    @Override
    public ToolResult afterToolCall(ContentBlock.ToolCall call, ToolResult result, LoopState state) {
        if (!enabled.get()) {
            return result;
        }
        if (!result.isError() && rules.stream().anyMatch(r -> touchesBuildDefinition(r, call))) {
            buildChanged.set(true);
        }
        List<ContentBlock.ToolCall> turn = turnCalls(call, state);
        ToolResult out = result;
        for (VerifyRule rule : rules) {
            if (covers(rule, call) && lastOfTurn(rule, call, result, turn)) {
                out = runAfterWrites(rule, call, turn, out, state);
            }
        }
        return out;
    }

    /** Records the call; true when it is the turn's last call of the rule and one of them succeeded. */
    private boolean lastOfTurn(VerifyRule rule, ContentBlock.ToolCall call, ToolResult result,
        List<ContentBlock.ToolCall> turn) {
        if (!result.isError()) {
            succeeded.add(call.id());
        }
        List<String> matching = turn.stream().filter(c -> covers(rule, c)).map(ContentBlock.ToolCall::id).toList();
        if (!matching.isEmpty() && !matching.get(matching.size() - 1).equals(call.id())) {
            return false;
        }
        boolean anyWritten =
            matching.isEmpty() ? succeeded.contains(call.id()) : matching.stream().anyMatch(succeeded::contains);
        matching.forEach(succeeded::remove);
        succeeded.remove(call.id());
        return anyWritten;
    }

    private ToolResult runAfterWrites(VerifyRule rule, ContentBlock.ToolCall call, List<ContentBlock.ToolCall> turn,
        ToolResult out, LoopState state) {
        List<ContentBlock.ToolCall> writes = turn.isEmpty() ? List.of(call) : turn;
        if (buildChanged.get() || writes.stream().anyMatch(c -> touchesBuildDefinition(rule, c))) {
            notifier.accept(SKIPPED);
            return report(out, rule,
                "[verify: " + rule.run() + "] skipped: build definition changed; the user can " + "run /verify");
        }
        if (!consent.allowed(commands())) {
            return out;
        }
        List<String> paths = writes.stream().filter(c -> covers(rule, c)).map(this::relative)
            .filter(p -> Files.isRegularFile(cwd.resolve(p))).distinct().toList();
        if (rule.run().contains(VerifyRunner.PATH_TOKEN) && paths.isEmpty()) {
            return out;
        }
        CancelToken cancel = state == null ? CancelToken.none() : state.cancel();
        return runner.run(rule, paths, cancel).map(o -> report(out, rule, o.report())).orElse(out);
    }

    /**
     * Runs every rule now, for {@code /verify}; stops at the first rule after cancellation.
     *
     * @param cancel cancellation of the command
     * @return the outcomes of the rules that ran
     */
    List<VerifyRunner.Outcome> runAll(CancelToken cancel) {
        List<VerifyRunner.Outcome> out = new ArrayList<>();
        for (VerifyRule r : rules) {
            if (cancel.isCancelled()) {
                break;
            }
            out.add(runner.run(r, cancel));
        }
        return out;
    }

    private boolean covers(VerifyRule rule, ContentBlock.ToolCall call) {
        JsonNode path = call.arguments().path(PATH_ARGUMENT);
        return path.isTextual() && rule.matches(call.name(), cwd, path.asText());
    }

    /** The call's path relative to the working directory, {@code /}-separated (covered calls are inside). */
    private String relative(ContentBlock.ToolCall call) {
        Path target = cwd.resolve(call.arguments().path(PATH_ARGUMENT).asText()).toAbsolutePath().normalize();
        return cwd.relativize(target).toString().replace('\\', '/');
    }

    private boolean touchesBuildDefinition(VerifyRule rule, ContentBlock.ToolCall call) {
        JsonNode path = call.arguments().path(PATH_ARGUMENT);
        if (!path.isTextual() || !rule.on().matcher(call.name()).matches()) {
            return false;
        }
        Path target = cwd.resolve(path.asText()).toAbsolutePath().normalize();
        String rel = (target.startsWith(cwd) ? cwd.relativize(target) : target).toString().replace('\\', '/');
        return BUILD_DEFINITIONS.stream().anyMatch(p -> p.matcher(rel).matches());
    }

    /** Calls of the reply this call belongs to, in order; empty when the state does not show it. */
    private static List<ContentBlock.ToolCall> turnCalls(ContentBlock.ToolCall call, LoopState state) {
        if (state == null) {
            return List.of();
        }
        List<Message> messages = state.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage a) {
                List<ContentBlock.ToolCall> calls = a.toolCalls();
                return calls.stream().anyMatch(c -> c.id().equals(call.id())) ? calls : List.of();
            }
        }
        return List.of();
    }

    private static ToolResult report(ToolResult result, VerifyRule rule, String text) {
        List<ContentBlock> content = new ArrayList<>(rule.replace() ? List.of() : result.content());
        content.add(new ContentBlock.Text(text));
        return new ToolResult(content, result.isError(), result.details());
    }
}
