/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.UserMessage;

/**
 * Adds a {@code <memory>} section to the system prompt of every request: the memories that best match the latest user
 * text, topped up with the most recent ones, at most {@value #MAX_ENTRIES} entries and {@value #MAX_CHARS} characters.
 * Can be switched off for the session.
 */
final class MemoryInjection implements AgentHooks {

    static final int MAX_ENTRIES = 5;
    static final int MAX_CHARS = 1500;
    static final String OPEN = "<memory>\nFacts the user asked you to remember (ids in brackets):";
    static final String CLOSE = "</memory>";

    private final MemoryStore store;
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    MemoryInjection(MemoryStore store) {
        this.store = store;
    }

    void enabled(boolean on) {
        enabled.set(on);
    }

    boolean enabled() {
        return enabled.get();
    }

    @Override
    public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
        if (!enabled.get()) {
            return request;
        }
        Optional<String> section = section(lastUserText(request.messages()));
        if (section.isEmpty()) {
            return request;
        }
        String system = request.systemPrompt().map(s -> s + "\n\n").orElse("") + section.get();
        return new ChatRequest(request.model(), Optional.of(system), request.messages(), request.tools(),
            request.options());
    }

    Optional<String> section(String query) {
        List<MemoryEntry> all = store.list();
        if (all.isEmpty()) {
            return Optional.empty();
        }
        Set<MemoryEntry> chosen = new LinkedHashSet<>();
        Recall.rank(all, query, MAX_ENTRIES).forEach(h -> chosen.add(h.entry()));
        all.stream().sorted(Comparator.comparing(MemoryEntry::createdAt).reversed()).limit(MAX_ENTRIES).forEach(e -> {
            if (chosen.size() < MAX_ENTRIES) {
                chosen.add(e);
            }
        });
        StringBuilder sb = new StringBuilder(OPEN);
        for (MemoryEntry e : chosen) {
            String line = "\n- [" + e.id() + "] " + e.text();
            if (sb.length() + line.length() + CLOSE.length() + 1 > MAX_CHARS) {
                break;
            }
            sb.append(line);
        }
        return Optional.of(sb.append('\n').append(CLOSE).toString());
    }

    static String lastUserText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage u) {
                List<String> parts = new ArrayList<>();
                u.content().forEach(b -> {
                    if (b instanceof ContentBlock.Text t) {
                        parts.add(t.text());
                    }
                });
                return String.join(" ", parts);
            }
        }
        return "";
    }
}
