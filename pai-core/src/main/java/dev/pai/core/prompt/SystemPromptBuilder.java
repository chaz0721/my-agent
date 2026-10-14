/* pai-next — Apache-2.0 */
package dev.pai.core.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Assembles the system prompt from a base text and named sections; rebuilt for every session.
 */
public final class SystemPromptBuilder {

    private static final String BASE_RESOURCE = "base.md";

    private String base = "";
    private final List<Section> sections = new ArrayList<>();

    /**
     * Returns the bundled default base prompt.
     *
     * @return the text of {@code base.md}
     */
    public static String defaultBase() {
        try (InputStream in =
            Objects.requireNonNull(SystemPromptBuilder.class.getResourceAsStream(BASE_RESOURCE), BASE_RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Sets the base text.
     *
     * @param text the base
     * @return this
     */
    public SystemPromptBuilder base(String text) {
        this.base = text.strip();
        return this;
    }

    /**
     * Appends a section rendered as {@code # name} followed by the text.
     *
     * @param name section heading
     * @param text section body
     * @return this
     */
    public SystemPromptBuilder section(String name, String text) {
        sections.add(new Section(name, text.strip()));
        return this;
    }

    /**
     * Renders the prompt.
     *
     * @return base followed by sections, separated by blank lines
     */
    public String build() {
        StringBuilder sb = new StringBuilder(base);
        for (Section s : sections) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("# ").append(s.name()).append("\n\n").append(s.text());
        }
        return sb.toString();
    }

    private record Section(String name, String text) {
    }
}
