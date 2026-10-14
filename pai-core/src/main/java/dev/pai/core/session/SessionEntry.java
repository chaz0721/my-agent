/* pai-next — Apache-2.0 */
package dev.pai.core.session;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.Message;

/**
 * One line of a session file. {@code parentId} links entries into a tree (ADR 0004).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = SessionEntry.MessageEntry.class, name = "message"),
    @JsonSubTypes.Type(value = SessionEntry.ModelChangeEntry.class, name = "model_change"),
    @JsonSubTypes.Type(value = SessionEntry.CompactionEntry.class, name = "compaction"),
    @JsonSubTypes.Type(value = SessionEntry.LabelEntry.class, name = "label"),
    @JsonSubTypes.Type(value = SessionEntry.CustomEntry.class, name = "custom")})
public sealed interface SessionEntry permits SessionEntry.MessageEntry, SessionEntry.ModelChangeEntry,
    SessionEntry.CompactionEntry, SessionEntry.LabelEntry, SessionEntry.CustomEntry {

    /**
     * Returns the entry id, unique within the session; empty asks the store to assign one.
     *
     * @return the id
     */
    String id();

    /**
     * Returns the parent entry id; empty for a root entry.
     *
     * @return the parent id
     */
    Optional<String> parentId();

    /**
     * Returns when the entry was created.
     *
     * @return the timestamp
     */
    Instant timestamp();

    /**
     * A message of the conversation.
     *
     * @param id entry id
     * @param parentId parent entry id
     * @param timestamp creation time
     * @param message the message
     */
    record MessageEntry(String id, Optional<String> parentId, Instant timestamp,
        Message message) implements SessionEntry {

        /**
         * Validates fields.
         */
        public MessageEntry {
            Objects.requireNonNull(id, "id");
            parentId = parentId == null ? Optional.empty() : parentId;
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * The model changed from this point on.
     *
     * @param id entry id
     * @param parentId parent entry id
     * @param timestamp creation time
     * @param providerId new provider
     * @param modelId new model
     */
    record ModelChangeEntry(String id, Optional<String> parentId, Instant timestamp, String providerId,
        String modelId) implements SessionEntry {

        /**
         * Validates fields.
         */
        public ModelChangeEntry {
            Objects.requireNonNull(id, "id");
            parentId = parentId == null ? Optional.empty() : parentId;
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    /**
     * Messages before {@code firstKeptEntryId} are replaced by {@code summary} when building context.
     *
     * @param id entry id
     * @param parentId parent entry id
     * @param timestamp creation time
     * @param summary summary of the replaced messages
     * @param firstKeptEntryId first entry kept verbatim
     * @param tokensBefore context size before compaction
     */
    record CompactionEntry(String id, Optional<String> parentId, Instant timestamp, String summary,
        String firstKeptEntryId, long tokensBefore) implements SessionEntry {

        /**
         * Validates fields.
         */
        public CompactionEntry {
            Objects.requireNonNull(id, "id");
            parentId = parentId == null ? Optional.empty() : parentId;
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(firstKeptEntryId, "firstKeptEntryId");
        }
    }

    /**
     * A user label on another entry.
     *
     * @param id entry id
     * @param parentId parent entry id
     * @param timestamp creation time
     * @param targetId labelled entry
     * @param label label text
     */
    record LabelEntry(String id, Optional<String> parentId, Instant timestamp, String targetId,
        String label) implements SessionEntry {

        /**
         * Validates fields.
         */
        public LabelEntry {
            Objects.requireNonNull(id, "id");
            parentId = parentId == null ? Optional.empty() : parentId;
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    /**
     * Extension-owned data; ignored when building context.
     *
     * @param id entry id
     * @param parentId parent entry id
     * @param timestamp creation time
     * @param customType extension-defined type
     * @param data payload
     */
    record CustomEntry(String id, Optional<String> parentId, Instant timestamp, String customType,
        JsonNode data) implements SessionEntry {

        /**
         * Validates fields.
         */
        public CustomEntry {
            Objects.requireNonNull(id, "id");
            parentId = parentId == null ? Optional.empty() : parentId;
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(customType, "customType");
            Objects.requireNonNull(data, "data");
        }
    }
}
