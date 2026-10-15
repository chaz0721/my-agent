/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;

/**
 * Checks a structured summary before it may replace messages: all six sections present, {@code Files touched} not empty
 * when the summarized messages wrote files, and within the length budget. A summary that fails is not committed.
 */
final class SummaryValidator {

    /** Required section headings, in order. */
    static final List<String> SECTIONS =
        List.of("## Task", "## Done", "## Files touched", "## Decisions", "## Open issues", "## Next step");
    /** Heading of the section that lists files. */
    static final String FILES = "## Files touched";
    /** Tool calls that write the file named by their {@code path} argument. */
    static final Set<String> WRITE_TOOLS = Set.of("write", "edit");

    private static final String HEADING = "## ";
    private static final String BULLET = "- ";
    private static final String NONE = "none";

    private SummaryValidator() {}

    /**
     * Lists what is wrong with a summary.
     *
     * @param summary the summary
     * @param summarized the messages it replaces
     * @param maxChars length budget
     * @return problems, empty when the summary is acceptable
     */
    static List<String> problems(String summary, List<Message> summarized, int maxChars) {
        List<String> out = new ArrayList<>();
        List<String> lines = summary.lines().map(String::strip).toList();
        for (String s : SECTIONS) {
            if (!lines.contains(s)) {
                out.add("missing section " + s);
            }
        }
        if (lines.contains(FILES) && filesBody(lines).isEmpty() && !writtenPaths(summarized).isEmpty()) {
            out.add("Files touched is empty although files were written: " + writtenPaths(summarized));
        }
        if (summary.length() > maxChars) {
            out.add("summary has " + summary.length() + " characters, more than " + maxChars);
        }
        return out;
    }

    /**
     * Removes duplicate file lines from {@code Files touched} and adds written files the summary left out.
     *
     * @param summary an acceptable summary
     * @param summarized the messages it replaces
     * @return the summary with a complete, duplicate-free file list
     */
    static String normalize(String summary, List<Message> summarized) {
        List<String> lines = summary.lines().toList();
        int start = lines.stream().map(String::strip).toList().indexOf(FILES);
        if (start < 0) {
            return summary;
        }
        int end = start + 1;
        while (end < lines.size() && !lines.get(end).strip().startsWith(HEADING)) {
            end++;
        }
        List<String> files = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String l : lines.subList(start + 1, end)) {
            String key = pathOf(l);
            if (key.isEmpty() || seen.add(key)) {
                files.add(l);
            }
        }
        String listed = String.join("\n", files);
        for (String p : writtenPaths(summarized)) {
            if (!listed.contains(p) && !listed.contains(fileName(p))) {
                insertBeforeBlankTail(files, BULLET + p + ": changed (details not summarized)");
            }
        }
        boolean listsFiles = files.stream().anyMatch(l -> !pathOf(l).isEmpty());
        files.removeIf(l -> listsFiles && l.strip().equalsIgnoreCase(BULLET + NONE));
        List<String> out = new ArrayList<>(lines.subList(0, start + 1));
        out.addAll(files);
        out.addAll(lines.subList(end, lines.size()));
        return String.join("\n", out);
    }

    /** Paths of the write/edit calls among the messages, in call order. */
    static Set<String> writtenPaths(List<Message> messages) {
        Set<String> out = new LinkedHashSet<>();
        for (Message m : messages) {
            if (m instanceof AssistantMessage a) {
                for (ContentBlock.ToolCall c : a.toolCalls()) {
                    String p = c.arguments().path("path").asText("");
                    if (WRITE_TOOLS.contains(c.name()) && !p.isBlank()) {
                        out.add(p.strip());
                    }
                }
            }
        }
        return out;
    }

    private static List<String> filesBody(List<String> lines) {
        List<String> body = new ArrayList<>();
        for (int i = lines.indexOf(FILES) + 1; i < lines.size() && !lines.get(i).startsWith(HEADING); i++) {
            String l = lines.get(i);
            String item = l.startsWith(BULLET) ? l.substring(BULLET.length()).strip() : l;
            if (!item.isEmpty() && !NONE.equals(item.toLowerCase(Locale.ROOT).replace(".", ""))) {
                body.add(l);
            }
        }
        return body;
    }

    /** The path a file line names: the bullet text up to the first colon, without backticks; empty for others. */
    private static String pathOf(String line) {
        String l = line.strip();
        if (!l.startsWith(BULLET)) {
            return "";
        }
        String item = l.substring(BULLET.length());
        int colon = item.indexOf(": ");
        String path = (colon < 0 ? item : item.substring(0, colon)).replace("`", "").replace("*", "").strip();
        return NONE.equalsIgnoreCase(path) ? "" : path;
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(slash + 1);
    }

    private static void insertBeforeBlankTail(List<String> files, String line) {
        int at = files.size();
        while (at > 0 && files.get(at - 1).isBlank()) {
            at--;
        }
        files.add(at, line);
    }
}
