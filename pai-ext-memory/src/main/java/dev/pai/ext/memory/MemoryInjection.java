/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.model.ChatRequest;

/**
 * Adds a {@code <memory>} section to the system prompt: the {@value #MAX_ENTRIES} most recent memories (at most
 * {@value #MAX_CHARS} characters), taken when the session starts and kept unchanged for the whole session, so the
 * system prompt stays stable (and cacheable) whatever the user types. Memories added during the session show up in the
 * next one; the {@code recall} tool finds them at any time. Can be switched off for the session.
 */
final class MemoryInjection implements AgentHooks {

    static final int MAX_ENTRIES = 5;
    static final int MAX_CHARS = 1500;
    static final String OPEN = "<memory>\nFacts the user asked you to remember (ids in brackets):";
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

    /** The most recent memories, newest first, within the budget; empty when there are none. */
    Optional<String> section() {
        List<MemoryEntry> recent = store.list().stream().sorted(Comparator.comparing(MemoryEntry::createdAt).reversed())
            .limit(MAX_ENTRIES).toList();
        if (recent.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder(OPEN);
        for (MemoryEntry e : recent) {
            String line = "\n- [" + e.id() + "] " + e.text();
            if (sb.length() + line.length() + CLOSE.length() + 1 > MAX_CHARS) {
                break;
            }
            sb.append(line);
        }
        return Optional.of(sb.append('\n').append(CLOSE).toString());
    }
}
