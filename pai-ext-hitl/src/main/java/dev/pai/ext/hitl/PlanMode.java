/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.model.AssistantMessage;
import dev.pai.model.ChatRequest;
import dev.pai.model.StopReasonEnum;
import dev.pai.model.ToolDefinition;

/**
 * Read-only planning on top of {@link ApprovalHooks}. While the mode is {@link ApprovalModeEnum#PLAN} every request
 * gets a plan-mode section and loses the tools that are not read-only ({@code bash} stays for its read-only commands);
 * the latest assistant reply is kept as the plan. {@code /plan save} writes it to {@value #PLAN_FILE} under the working
 * directory, and the next run outside plan mode gets it once as a {@code <plan>} section.
 */
final class PlanMode implements AgentHooks {

    /** Where {@code /plan save} writes, relative to the working directory. */
    static final String PLAN_FILE = ".pai/plan.md";
    static final String SECTION = "<plan-mode>\nPlan mode is on and you are read-only. Explore the code with read-only "
        + "tools (bash only for ls, cat, head, tail, grep, rg, find, git status/log/diff/show, wc, tree), then reply "
        + "with a numbered step-by-step plan. Do not try to change files or run other commands: those calls are "
        + "blocked.\n</plan-mode>";
    static final String PLAN_OPEN = "<plan>\nThe plan the user approved; carry it out:\n";
    static final String PLAN_CLOSE = "\n</plan>";

    private final ApprovalHooks approvals;
    private final Function<String, Optional<Tool>> tools;
    private final Path cwd;
    private ApprovalModeEnum before = HitlSettings.DEFAULT_MODE;
    private String latestReply = "";
    private boolean saved;
    private boolean injecting;

    PlanMode(ApprovalHooks approvals, Function<String, Optional<Tool>> tools, Path cwd) {
        this.approvals = approvals;
        this.tools = tools;
        this.cwd = cwd;
    }

    /** Enters plan mode, remembering the mode to return to. */
    synchronized void enter() {
        if (approvals.mode() != ApprovalModeEnum.PLAN) {
            before = approvals.mode();
            latestReply = "";
            approvals.mode(ApprovalModeEnum.PLAN);
        }
    }

    /** Leaves plan mode for the mode that was active before it. */
    synchronized ApprovalModeEnum leave() {
        if (approvals.mode() == ApprovalModeEnum.PLAN) {
            approvals.mode(before);
        }
        return approvals.mode();
    }

    /** Switches to a mode; entering plan mode goes through {@link #enter()}. */
    synchronized void switchTo(ApprovalModeEnum next) {
        if (next == ApprovalModeEnum.PLAN) {
            enter();
        } else {
            approvals.mode(next);
        }
    }

    /**
     * Writes the latest plan-mode reply to {@value #PLAN_FILE} and arms its injection.
     *
     * @return the file written, empty when there is no plan yet
     * @throws IOException when the file cannot be written
     */
    synchronized Optional<Path> save() throws IOException {
        if (latestReply.isBlank()) {
            return Optional.empty();
        }
        Path file = cwd.resolve(PLAN_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, latestReply.strip() + "\n", StandardCharsets.UTF_8);
        saved = true;
        return Optional.of(file);
    }

    /** Keeps a final assistant reply given in plan mode (stopped normally, no tool calls) as the plan. */
    synchronized void replied(AssistantMessage message) {
        boolean finalReply = message.stopReason() == StopReasonEnum.STOP && message.toolCalls().isEmpty();
        if (approvals.mode() == ApprovalModeEnum.PLAN && finalReply && !message.text().isBlank()) {
            latestReply = message.text();
        }
    }

    /** A run ended: a plan injected during it is not injected again. */
    synchronized void runEnded() {
        if (injecting) {
            injecting = false;
            saved = false;
        }
    }

    @Override
    public synchronized ChatRequest prepareRequest(ChatRequest request, LoopState state) {
        if (approvals.mode() == ApprovalModeEnum.PLAN) {
            List<ToolDefinition> kept = request.tools().stream().filter(this::readOnly).toList();
            return with(request, SECTION, kept);
        }
        if (!saved) {
            return request;
        }
        Optional<String> plan = readPlan();
        if (plan.isEmpty()) {
            saved = false;
            return request;
        }
        injecting = true;
        return with(request, PLAN_OPEN + plan.get() + PLAN_CLOSE, request.tools());
    }

    private Optional<String> readPlan() {
        try {
            return Optional.of(Files.readString(cwd.resolve(PLAN_FILE), StandardCharsets.UTF_8).strip());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private boolean readOnly(ToolDefinition d) {
        return ReadOnlyCommands.BASH.equals(d.name())
            || tools.apply(d.name()).map(t -> t.effect() == ToolEffectEnum.READ_ONLY).orElse(false);
    }

    private static ChatRequest with(ChatRequest request, String section, List<ToolDefinition> tools) {
        String system = request.systemPrompt().map(s -> s + "\n\n").orElse("") + section;
        return new ChatRequest(request.model(), Optional.of(system), request.messages(), tools, request.options());
    }
}
