/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.model.ChatRequest;

/**
 * Adds a {@code <memory>} section to the system prompt: the first lines of the project's {@code MEMORY.md}, then of the
 * global one, at most {@value #MAX_LINES} lines and {@value #MAX_CHARS} characters, read when the session starts and
 * kept unchanged for the whole session, so the system prompt stays stable (and cacheable) whatever the user types or
 * edits meanwhile. Topic files are not injected; the {@code recall} tool reads them on demand. Can be switched off for
 * the session.
 */
final class MemoryInjection implements AgentHooks {

    static final int MAX_LINES = 60;
    static final int MAX_CHARS = 4000;
    static final String OPEN = "<memory>\nFacts the user asked you to remember, from MEMORY.md (project first, then "
        + "global; ids in brackets; topic files via the recall tool):";
    static final String CLOSE = "</memory>";

    private final MemoryStore store;
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private Optional<String> pinned = Optional.empty();
    private boolean taken;

    MemoryInjection(MemoryStore store) {
        this.store = store;
    }

    void enabled(boolean on) {
        enabled.set(on);
    }

    boolean enabled() {
        return enabled.get();
    }

    /** Takes the section for a new session. */
    synchronized void sessionStarted() {
        pinned = section();
        taken = true;
    }

    /** The section of this session, taken on first use when no session start was seen. */
    synchronized Optional<String> pinned() {
        if (!taken) {
            sessionStarted();
        }
        return pinned;
    }

    @Override
    public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
        if (!enabled.get()) {
            return request;
        }
        Optional<String> section = pinned();
        if (section.isEmpty()) {
            return request;
        }
        String system = request.systemPrompt().map(s -> s + "\n\n").orElse("") + section.get();
        return new ChatRequest(request.model(), Optional.of(system), request.messages(), request.tools(),
            request.options());
    }

    /** The index lines within the budget, project first; empty when there are none. */
    Optional<String> section() {
        StringBuilder sb = new StringBuilder(OPEN);
        int lines = 0;
        for (String scope : List.of(MemoryStore.PROJECT, MemoryStore.GLOBAL)) {
            List<String> index = store.indexLines(scope);
            if (index.isEmpty()) {
                continue;
            }
            String head = "\n[" + scope + "]";
            for (String l : index) {
                String line = head + "\n" + l;
                lines++;
                if (lines > MAX_LINES || sb.length() + line.length() + CLOSE.length() + 1 > MAX_CHARS) {
                    return close(sb);
                }
                sb.append(line);
                head = "";
            }
        }
        return close(sb);
    }

    private static Optional<String> close(StringBuilder sb) {
        return sb.length() == OPEN.length() ? Optional.empty() : Optional.of(sb.append('\n').append(CLOSE).toString());
    }
}
