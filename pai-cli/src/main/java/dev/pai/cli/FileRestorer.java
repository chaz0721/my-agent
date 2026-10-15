/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import dev.pai.core.compaction.CharsTokenEstimatorImpl;
import dev.pai.core.session.SessionTree;
import dev.pai.core.tool.RealPaths;
import dev.pai.tui.TerminalInteractionImpl;

/**
 * What a compaction gives back after its summary: the current content of the files {@code ## Files touched} lists,
 * latest first, so that the model can edit them (an exact {@code oldText}) without reading them again. Up to
 * {@value #MAX_FILES} files, each at most {@value #MAX_FILE_TOKENS} tokens and all at most {@value #MAX_TOTAL_TOKENS}
 * (less when the compaction leaves less room, so that a small window is not overrun right after its summary); a path is
 * resolved like the path guard does (links followed, real path) and must stay inside the working directory. Missing,
 * outside, too large or non-UTF-8 files are skipped, and so are files that look like secrets ({@code .env*},
 * {@code .npmrc}, {@code .netrc}, {@code .pgpass}, keys, keystores, {@code id_*}, {@code *credential*},
 * {@code *secret*}; by the listed or the real name), with a notice.
 */
final class FileRestorer {

    static final int MAX_FILES = 5;
    static final int MAX_FILE_TOKENS = 5_000;
    static final int MAX_TOTAL_TOKENS = 25_000;
    static final String OPEN =
        SessionTree.RESTORED_FILES_TAG + "\nCurrent content of files the summary lists as touched, latest "
            + "first; edit them directly, there is no need to read them again.\n";
    static final String CLOSE = "</restored-files>";
    /** UTF-8 needs at most this many bytes per character, so larger files cannot fit. */
    private static final int MAX_BYTES_PER_CHAR = 4;
    private static final Pattern SECRET = Pattern.compile(
        "\\.env.*|\\.(npmrc|netrc|pgpass)|.*\\.(pem|key|p12|pfx|jks|keystore|kdbx)|id_.*|.*credential.*|.*secret.*",
        Pattern.CASE_INSENSITIVE);

    private final Optional<Path> root;
    private final Consumer<String> notices;

    FileRestorer(Path cwd, Consumer<String> notices) {
        this.notices = notices;
        Optional<Path> r;
        try {
            r = Optional.of(cwd.toRealPath());
        } catch (IOException e) {
            r = Optional.empty();
        }
        this.root = r;
    }

    /** The {@code <restored-files>} text for the listed paths within {@code room} tokens, empty when none fits. */
    Optional<String> restore(List<String> paths, int room) {
        long budget = Math.min(MAX_TOTAL_TOKENS, room);
        StringBuilder sb = new StringBuilder(OPEN);
        Set<Path> seen = new HashSet<>();
        int files = 0;
        long total = 0L;
        for (int i = paths.size() - 1; i >= 0 && files < MAX_FILES && root.isPresent(); i--) {
            String listed = paths.get(i);
            Optional<Path> file = inside(listed).filter(seen::add).filter(f -> !secret(listed, f));
            Optional<String> text = file.flatMap(FileRestorer::read);
            long tokens = text.map(t -> (long)t.length() / CharsTokenEstimatorImpl.CHARS_PER_TOKEN + 1L).orElse(0L);
            if (text.isEmpty() || tokens > MAX_FILE_TOKENS || total + tokens > budget) {
                continue;
            }
            total += tokens;
            files++;
            String rel = root.get().relativize(file.get()).toString().replace('\\', '/');
            sb.append("<file path=\"").append(rel).append("\">\n").append(text.get())
                .append(text.get().endsWith("\n") ? "" : "\n").append("</file>\n");
        }
        return files == 0 ? Optional.empty() : Optional.of(sb.append(CLOSE).toString());
    }

    private boolean secret(String listed, Path real) {
        String name = listed.replace('\\', '/').substring(listed.replace('\\', '/').lastIndexOf('/') + 1);
        boolean hit = SECRET.matcher(name).matches() || SECRET.matcher(real.getFileName().toString()).matches();
        if (hit) {
            notices.accept(
                "compaction: not restoring " + TerminalInteractionImpl.visible(listed) + " (looks like a " + "secret)");
        }
        return hit;
    }

    /** The real path of an existing regular file inside the working directory. */
    private Optional<Path> inside(String path) {
        return RealPaths.inside(root.orElseThrow(), path).filter(Files::isRegularFile);
    }

    private static Optional<String> read(Path file) {
        try {
            if (Files.size(file) > (long)MAX_FILE_TOKENS * CharsTokenEstimatorImpl.CHARS_PER_TOKEN
                * MAX_BYTES_PER_CHAR) {
                return Optional.empty();
            }
            return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(file)))
                .toString());
        } catch (IOException e) {
            // also CharacterCodingException: not UTF-8 text
            return Optional.empty();
        }
    }
}
