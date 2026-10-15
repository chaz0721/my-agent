/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Memory files meant to be edited by hand, under {@code <paiHome>/memory/<scope>/}: the scope directory is
 * {@code global} or the hash of the working directory (with a {@code path.txt} naming it), and holds the index
 * {@code MEMORY.md} plus optional topic files {@code <topic>.md}. Every read goes to the files, so edits made in an
 * editor show at once. Methods are synchronized.
 */
final class MemoryStore {

    static final String GLOBAL = "global";
    static final String PROJECT = "project";
    static final String DIR = "memory";
    static final String INDEX = "MEMORY.md";
    static final String PATH_NOTE = "path.txt";
    static final String TOPIC_PREFIX = "topic-";
    static final Pattern TOPIC = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    private static final String MD = ".md";
    private static final int ID_LENGTH = 8;
    private static final int HASH_HEX = 16;
    private static final int INDEX_TEXT = 80;

    private final Path root;
    private final Path cwd;
    private final Path globalDir;
    private final Path projectDir;

    MemoryStore(Path paiHome, Path cwd) {
        this.root = paiHome.resolve(DIR);
        this.cwd = cwd.toAbsolutePath().normalize();
        this.globalDir = root.resolve(GLOBAL);
        this.projectDir = root.resolve(hash(this.cwd.toString()));
    }

    Path dir(String scope) {
        return GLOBAL.equals(scope) ? globalDir : projectDir;
    }

    /** The scope's index file. */
    Path file(String scope) {
        return dir(scope).resolve(INDEX);
    }

    /** Entries of both index files, project first, in file order. */
    synchronized List<MemoryEntry> list() {
        List<MemoryEntry> out = new ArrayList<>(entries(file(PROJECT), PROJECT, Optional.empty()));
        out.addAll(entries(file(GLOBAL), GLOBAL, Optional.empty()));
        return out;
    }

    /** Entries of the index files and of every topic file. */
    synchronized List<MemoryEntry> all() {
        List<MemoryEntry> out = new ArrayList<>(list());
        for (String scope : List.of(PROJECT, GLOBAL)) {
            for (String t : topics(scope)) {
                out.addAll(entries(dir(scope).resolve(t + MD), scope, Optional.of(t)));
            }
        }
        return out;
    }

    /** The non-blank, non-comment lines of a scope's index, as written. */
    synchronized List<String> indexLines(String scope) {
        return MemoryFile.read(file(scope)).stream().map(String::strip)
            .filter(l -> !l.isEmpty() && !l.startsWith(MemoryFile.COMMENT)).toList();
    }

    /**
     * Stores a fact: in the index, or with a topic in {@code <topic>.md} plus one index line for the topic.
     */
    synchronized MemoryEntry add(String text, List<String> tags, String scope, Optional<String> topic) {
        List<MemoryEntry> known = all();
        String id;
        do {
            id = UUID.randomUUID().toString().substring(0, ID_LENGTH);
        } while (find(known, id).isPresent());
        MemoryEntry e = new MemoryEntry(id, text.strip().replaceAll("\\s*\\R\\s*", " "), tags, scope, topic);
        if (topic.isEmpty()) {
            MemoryFile.append(file(scope), MemoryFile.format(e), header(scope));
            return e;
        }
        MemoryFile.append(dir(scope).resolve(topic.get() + MD), MemoryFile.format(e),
            "<!-- pai memory topic " + topic.get() + ". One memory per line: - [id] text #tag -->");
        String indexId = TOPIC_PREFIX + topic.get();
        if (find(entries(file(scope), scope, Optional.empty()), indexId).isEmpty()) {
            String gist = e.text().length() <= INDEX_TEXT ? e.text() : e.text().substring(0, INDEX_TEXT) + "...";
            MemoryFile.append(file(scope), "- [" + indexId + "] topic " + topic.get() + ": " + gist
                + " (recall topic \"" + topic.get() + "\" for more)", header(scope));
        }
        return e;
    }

    /** Removes the lines with this id from every memory file; returns whether there was one. */
    synchronized boolean delete(String id) {
        return removeLines(e -> e.id().equals(id)) > 0;
    }

    /** Removes every memory line of both scopes (other lines stay); returns how many. */
    synchronized int clear() {
        return removeLines(e -> true);
    }

    /** The text of a topic file of either scope, project first. */
    synchronized Optional<String> topic(String name) {
        StringBuilder sb = new StringBuilder();
        for (String scope : List.of(PROJECT, GLOBAL)) {
            Path f = dir(scope).resolve(name + MD);
            if (TOPIC.matcher(name).matches() && Files.isRegularFile(f)) {
                sb.append("[").append(scope).append("] ").append(f).append('\n');
                MemoryFile.read(f).forEach(l -> sb.append(l).append('\n'));
            }
        }
        return sb.isEmpty() ? Optional.empty() : Optional.of(sb.toString().stripTrailing());
    }

    /** Topic names of a scope, sorted. */
    synchronized List<String> topics(String scope) {
        Path d = dir(scope);
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(d)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(MD) && !INDEX.equals(n))
                .map(n -> n.substring(0, n.length() - MD.length())).filter(n -> TOPIC.matcher(n).matches()).sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + d, e);
        }
    }

    /** Converts the JSONL files of earlier versions once; returns how many memories were moved. */
    synchronized int migrate() {
        int n = MemoryMigration.run(root.resolve(GLOBAL + ".jsonl"), file(GLOBAL), GLOBAL, header(GLOBAL));
        n += MemoryMigration.run(root.resolve(projectDir.getFileName() + ".jsonl"), file(PROJECT), PROJECT,
            header(PROJECT));
        if (n > 0) {
            notePath();
        }
        return n;
    }

    private String header(String scope) {
        if (PROJECT.equals(scope)) {
            notePath();
        }
        String what = GLOBAL.equals(scope) ? "global" : "project " + cwd;
        return "<!-- pai memory, " + what + ". One memory per line: - [id] text #tag. Edit freely. -->";
    }

    private void notePath() {
        Path note = projectDir.resolve(PATH_NOTE);
        if (Files.exists(note)) {
            return;
        }
        try {
            Files.createDirectories(projectDir);
            Files.writeString(note, cwd + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + note, e);
        }
    }

    private int removeLines(Predicate<MemoryEntry> drop) {
        int removed = 0;
        for (String scope : List.of(PROJECT, GLOBAL)) {
            List<Path> files = new ArrayList<>(List.of(file(scope)));
            topics(scope).forEach(t -> files.add(dir(scope).resolve(t + MD)));
            for (Path f : files) {
                List<String> lines = MemoryFile.read(f);
                List<String> kept = lines.stream()
                    .filter(l -> MemoryFile.parse(l, scope, Optional.empty()).filter(drop).isEmpty()).toList();
                if (kept.size() != lines.size()) {
                    removed += lines.size() - kept.size();
                    MemoryFile.write(f, kept);
                }
            }
        }
        return removed;
    }

    private static List<MemoryEntry> entries(Path f, String scope, Optional<String> topic) {
        return MemoryFile.read(f).stream().map(l -> MemoryFile.parse(l, scope, topic)).flatMap(Optional::stream)
            .toList();
    }

    private static Optional<MemoryEntry> find(List<MemoryEntry> entries, String id) {
        return entries.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** Lower-case topic name, or empty when it is not a valid one. */
    static Optional<String> topicName(String raw) {
        String t = raw.strip().toLowerCase(Locale.ROOT);
        return TOPIC.matcher(t).matches() ? Optional.of(t) : Optional.empty();
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, HASH_HEX);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }
}
