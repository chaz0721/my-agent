/* pai-next — Apache-2.0 */
package dev.pai.ext.verify;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * call of the turn succeeded.
 */
final class VerifyHooks implements AgentHooks {

    static final String PATH_ARGUMENT = "path";

    private final List<VerifyRule> rules;
    private final VerifyRunner runner;
    private final Path cwd;
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final Set<String> succeeded = ConcurrentHashMap.newKeySet();

    VerifyHooks(List<VerifyRule> rules, VerifyRunner runner, Path cwd) {
        this.rules = List.copyOf(rules);
        this.runner = runner;
        this.cwd = cwd.toAbsolutePath().normalize();
    }

    void enabled(boolean on) {
        enabled.set(on);
    }

    boolean enabled() {
        return enabled.get();
    }

    List<VerifyRule> rules() {
        return rules;
    }

    @Override
    public ToolResult afterToolCall(ContentBlock.ToolCall call, ToolResult result, LoopState state) {
        if (!enabled.get()) {
            return result;
        }
        List<ContentBlock.ToolCall> turn = turnCalls(call, state);
        ToolResult out = result;
        for (VerifyRule rule : rules) {
            if (!covers(rule, call)) {
                continue;
            }
            if (!result.isError()) {
                succeeded.add(call.id());
            }
            List<String> matching = turn.stream().filter(c -> covers(rule, c)).map(ContentBlock.ToolCall::id).toList();
            boolean last = matching.isEmpty() || matching.get(matching.size() - 1).equals(call.id());
            if (!last) {
                continue;
            }
            boolean anyWritten =
                matching.isEmpty() ? !result.isError() : matching.stream().anyMatch(succeeded::contains);
            matching.forEach(succeeded::remove);
            succeeded.remove(call.id());
            if (anyWritten) {
                CancelToken cancel = state == null ? CancelToken.none() : state.cancel();
                out = report(out, rule, runner.run(rule, cancel));
            }
        }
        return out;
    }

    /** Runs every rule now, for {@code /verify}. */
    List<VerifyRunner.Outcome> runAll() {
        List<VerifyRunner.Outcome> out = new ArrayList<>();
        rules.forEach(r -> out.add(runner.run(r, CancelToken.none())));
        return out;
    }

    private boolean covers(VerifyRule rule, ContentBlock.ToolCall call) {
        JsonNode path = call.arguments().path(PATH_ARGUMENT);
        return path.isTextual() && rule.matches(call.name(), cwd, path.asText());
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

    private static ToolResult report(ToolResult result, VerifyRule rule, VerifyRunner.Outcome outcome) {
        List<ContentBlock> content = new ArrayList<>(rule.replace() ? List.of() : result.content());
        content.add(new ContentBlock.Text(outcome.report()));
        return new ToolResult(content, result.isError(), result.details());
    }
}
