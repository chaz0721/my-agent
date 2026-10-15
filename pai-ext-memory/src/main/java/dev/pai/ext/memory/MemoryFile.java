/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The line format of memory files, which people edit by hand: {@code - [id] text #tag #tag}. A bullet without an id
 * (written by hand) gets an id derived from its text; other lines (headings, comments, prose) are kept but are not
 * memories. Files are rewritten through a temporary file and a move.
 */
final class MemoryFile {

    static final String COMMENT = "<!--";
    private static final Pattern WITH_ID = Pattern.compile("^\\s*[-*]\\s+\\[([A-Za-z0-9_.:-]{1,40})]\\s+(.+?)\\s*$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*]\\s+(.+?)\\s*$");
    private static final Pattern TAG = Pattern.compile("#[^\\s#]+");
    private static final int DERIVED_ID = 8;

    private MemoryFile() {}

    /** Parses a line; empty when it is not a memory. */
    static Optional<MemoryEntry> parse(String line, String scope, Optional<String> topic) {
        Matcher m = WITH_ID.matcher(line);
        String id;
        String rest;
        if (m.matches()) {
            id = m.group(1);
            rest = m.group(2);
        } else {
            Matcher b = BULLET.matcher(line);
            if (!b.matches()) {
                return Optional.empty();
            }
            rest = b.group(1);
            id = MemoryStore.hash(rest).substring(0, DERIVED_ID);
        }
        List<String> words = new ArrayList<>(List.of(rest.split("\\s+")));
        List<String> tags = new ArrayList<>();
        while (words.size() > 1 && TAG.matcher(words.get(words.size() - 1)).matches()) {
            tags.add(0, words.remove(words.size() - 1).substring(1));
        }
        return Optional.of(new MemoryEntry(id, String.join(" ", words), tags, scope, topic));
    }

    /** The line for an entry. */
    static String format(MemoryEntry e) {
        StringBuilder sb = new StringBuilder("- [").append(e.id()).append("] ").append(e.text());
        e.tags().forEach(t -> sb.append(" #").append(t));
        return sb.toString();
    }

    static List<String> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    static void write(Path file, List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, lines.isEmpty() ? "" : String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    /** Appends a line, creating the file with {@code header} first when it does not exist. */
    static void append(Path file, String line, String header) {
        List<String> lines = new ArrayList<>(read(file));
        if (lines.isEmpty() && !Files.exists(file)) {
            lines.add(header);
        }
        lines.add(line);
        write(file, lines);
    }
}
