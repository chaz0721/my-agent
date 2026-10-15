/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.Args;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ToolDefinition;

/**
 * {@code remember(text, tags?, scope?, topic?)}: stores a fact the user wants kept across sessions, in the scope's
 * {@code MEMORY.md} or, with a topic, in the topic file (indexed by one line in {@code MEMORY.md}). Refuses text that
 * looks like a secret. Writes a file, so approval applies.
 */
final class RememberTool implements Tool {

    static final String NAME = "remember";
    static final int MAX_TEXT = 500;
    static final int MAX_TAGS = 10;
    static final int MAX_TAG = 40;

    private final MemoryStore store;

    RememberTool(MemoryStore store) {
        this.store = store;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME,
            "Remember a short fact or preference the user explicitly asked you to keep for later sessions. "
                + "Never store secrets.",
            JsonSchema.object().string("text", "The fact, one or two sentences")
                .array("tags", "Optional tags", "string")
                .string("scope", "global (about the user, default) or project (about this directory)")
                .string("topic", "Optional topic file for details that need not be in every prompt, e.g. build")
                .required("text").build());
    }

    @Override
    public ToolEffectEnum effect() {
        return ToolEffectEnum.WRITE_FS;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) {
        Args args = Args.of(arguments);
        String text = args.string("text").strip();
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            return ToolResult.error("text must be 1 to " + MAX_TEXT + " characters");
        }
        Optional<String> secret = SecretFilter.detect(text);
        if (secret.isPresent()) {
            return ToolResult.error("refused: the text looks like it contains a secret (" + secret.get()
                + "); secrets are never stored in memory");
        }
        String scope = args.stringOr("scope", MemoryStore.GLOBAL);
        if (!MemoryStore.GLOBAL.equals(scope) && !MemoryStore.PROJECT.equals(scope)) {
            return ToolResult.error("scope must be global or project");
        }
        List<String> tags = new ArrayList<>();
        arguments.path("tags").forEach(t -> tags.add(t.asText().strip()));
        if (tags.size() > MAX_TAGS || tags.stream().anyMatch(t -> t.isEmpty() || t.length() > MAX_TAG)) {
            return ToolResult.error("at most " + MAX_TAGS + " tags of 1 to " + MAX_TAG + " characters each");
        }
        Optional<String> secretTag = tags.stream().map(SecretFilter::detect).flatMap(Optional::stream).findFirst();
        if (secretTag.isPresent()) {
            return ToolResult.error("refused: a tag looks like it contains a secret (" + secretTag.get()
                + "); secrets are never stored in memory");
        }
        Optional<String> topic = Optional.empty();
        if (!args.stringOr("topic", "").isBlank()) {
            topic = MemoryStore.topicName(args.stringOr("topic", ""));
            if (topic.isEmpty()) {
                return ToolResult.error("topic must be 1 to 40 lower-case letters, digits, '-' or '_'");
            }
        }
        MemoryEntry e = store.add(text, tags, scope, topic);
        return ToolResult.text(
            "remembered [" + e.id() + "] (" + scope + topic.map(t -> ", topic " + t).orElse("") + "): " + e.text());
    }
}
