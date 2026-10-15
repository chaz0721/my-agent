/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.pai.core.session.JsonlSessionStoreImpl;
import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.UserMessage;

/**
 * Checks a structured summary before it may replace messages: all six sections present, {@code Files touched} not empty
 * when the summarized messages wrote files, and within the length budget. A summary that fails is not committed.
 * Headings are first brought to their canonical form ({@link #canonical}), so {@code ### files touched:} or
 * {@code **Files touched**} count as {@code ## Files touched}.
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
    private static final Pattern LOOSE_HEADING =
        Pattern.compile("^(?:#{1,6}\\s*)?(?:\\*\\*)?\\s*(task|done|files touched|decisions|open issues|next step)"
            + "\\s*:?\\s*(?:\\*\\*)?\\s*:?$", Pattern.CASE_INSENSITIVE);

    private SummaryValidator() {}

    /**
     * Rewrites every line that is one of the six headings in a loose form (any number of {@code #}, bold, any case,
     * trailing colon, surrounding blanks) as the canonical heading.
     *
     * @param summary the summary as the model wrote it
     * @return the summary with canonical headings
     */
    static String canonical(String summary) {
        List<String> out = new ArrayList<>();
        for (String line : summary.lines().toList()) {
            String l = line.strip();
            Matcher m = LOOSE_HEADING.matcher(l);
            boolean marked = l.startsWith("#") || l.startsWith("**");
            out.add(marked && m.matches() ? heading(m.group(1)) : line);
        }
        return String.join("\n", out);
    }

    private static String heading(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SECTIONS.stream().filter(s -> s.toLowerCase(Locale.ROOT).equals(HEADING + lower)).findFirst()
            .orElseThrow();
    }

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
     * Removes duplicate file lines from {@code Files touched} and adds the files the summary left out: those listed by
     * the earlier summary the messages start with, and those written by a successful write/edit call. A file counts as
     * listed when its normalized path equals, or ends with, a listed normalized path (or the other way round).
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
        int end = sectionEnd(lines, start);
        List<String> files = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String l : lines.subList(start + 1, end)) {
            String key = key(pathOf(l));
            if (key.isEmpty() || seen.add(key)) {
                files.add(l);
            }
        }
        for (String l : earlierFiles(summarized)) {
            if (seen.stream().noneMatch(k -> same(k, key(pathOf(l))))) {
                seen.add(key(pathOf(l)));
                insertBeforeBlankTail(files, l.strip());
            }
        }
        for (String p : writtenPaths(summarized)) {
            if (seen.stream().noneMatch(k -> same(k, key(p)))) {
                seen.add(key(p));
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

    /** The file lines of the earlier summary that the messages start with, if they do. */
    private static List<String> earlierFiles(List<Message> messages) {
        if (messages.isEmpty() || !(messages.get(0) instanceof UserMessage u)) {
            return List.of();
        }
        String text = u.content().stream().filter(ContentBlock.Text.class::isInstance)
            .map(b -> ((ContentBlock.Text)b).text()).reduce("", String::concat);
        if (!text.startsWith(JsonlSessionStoreImpl.SUMMARY_PREFIX)) {
            return List.of();
        }
        List<String> lines = canonical(text).lines().toList();
        int start = lines.stream().map(String::strip).toList().indexOf(FILES);
        return start < 0 ? List.of()
            : lines.subList(start + 1, sectionEnd(lines, start)).stream().filter(l -> !pathOf(l).isEmpty()).toList();
    }

    private static int sectionEnd(List<String> lines, int start) {
        int end = start + 1;
        while (end < lines.size() && !lines.get(end).strip().startsWith(HEADING)) {
            end++;
        }
        return end;
    }

    /** Path compared case-insensitively, with forward slashes and without a leading {@code ./}. */
    private static String key(String path) {
        String p = path.strip().replace('\\', '/').toLowerCase(Locale.ROOT);
        return p.startsWith("./") ? p.substring(2) : p;
    }

    private static boolean same(String a, String b) {
        return !a.isEmpty() && !b.isEmpty() && (a.equals(b) || a.endsWith("/" + b) || b.endsWith("/" + a));
    }

    /** Paths of the write/edit calls among the messages whose result was not an error, in call order. */
    static Set<String> writtenPaths(List<Message> messages) {
        Set<String> failed = new HashSet<>();
        for (Message m : messages) {
            if (m instanceof ToolResultMessage r && r.isError()) {
                failed.add(r.toolCallId());
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (Message m : messages) {
            if (m instanceof AssistantMessage a) {
                for (ContentBlock.ToolCall c : a.toolCalls()) {
                    String p = c.arguments().path("path").asText("");
                    if (WRITE_TOOLS.contains(c.name()) && !p.isBlank() && !failed.contains(c.id())) {
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

    private static void insertBeforeBlankTail(List<String> files, String line) {
        int at = files.size();
        while (at > 0 && files.get(at - 1).isBlank()) {
            at--;
        }
        files.add(at, line);
    }
}
