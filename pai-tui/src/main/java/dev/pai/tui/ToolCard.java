/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;

/**
 * One tool call as a card: a header ({@code ▸ edit src/Main.java} while running, {@code ✓ edit src/Main.java · 0.3s}
 * when done), a sub-agent's progress line while it runs, and a body that is folded to its first lines with a hint
 * {@code … (+N lines, /expand K)}. The body is a coloured diff for {@code edit} and {@code write}, the output for
 * {@code bash} (the full command too when expanded), nothing for {@code read}-like calls and the result text otherwise.
 */
final class ToolCard {

    /** Body lines shown of a successful call by default. */
    static final int FOLD_LINES = 8;
    /** Body lines shown of a failed call by default. */
    static final int ERROR_FOLD_LINES = 20;
    /** Indent of body lines. */
    static final String INDENT = "  ";
    private static final double MILLIS_PER_SECOND = 1000.0d;
    private static final Pattern EXIT_LINE = Pattern.compile("\\n?\\[exit code: -?\\d+]\\s*$");
    private static final List<String> NO_BODY = List.of("read", "load_skill", TaskList.TOOL);

    private final int number;
    private final ContentBlock.ToolCall call;
    private String progress;
    private String turns;
    private ToolResult result;
    private Duration took = Duration.ZERO;

    ToolCard(int number, ContentBlock.ToolCall call) {
        this.number = number;
        this.call = call;
    }

    int number() {
        return number;
    }

    boolean isSubagent() {
        return EventView.SPAWN_AGENT.equals(call.name());
    }

    /** Shows a sub-agent's latest turn. */
    void progress(String[] turnLine) {
        turns = turnLine[1];
        progress = "turn " + turnLine[1] + ": " + turnLine[2];
    }

    void finish(ToolResult r, Duration d) {
        this.result = r;
        this.took = d;
    }

    /**
     * Lines of the running card for the live area.
     *
     * @param t the colours
     * @param frame the spinner frame
     * @param width terminal columns
     * @return the header and, for a sub-agent, its progress line
     */
    List<AttributedString> running(Theme t, String frame, int width) {
        List<AttributedString> out = new ArrayList<>();
        out.add(Cols.fit(
            new AttributedStringBuilder().append(frame + " ", t.tool()).append(describe(t, width)).toAttributedString(),
            width));
        if (progress != null) {
            out.add(Cols.fit(Theme.styled(INDENT + Cols.clean(progress), t.faint()), width));
        }
        return out;
    }

    /**
     * Lines of the finished card for the transcript.
     *
     * @param t the colours
     * @param s the glyphs
     * @param fold body lines to show at most; 0 shows all; a failed call shows at least {@value #ERROR_FOLD_LINES}
     * @param width terminal columns
     * @return the lines
     */
    List<AttributedString> finished(Theme t, Symbols s, int fold, int width) {
        boolean error = failed();
        List<String> facts = new ArrayList<>();
        if (isSubagent() && turns != null) {
            facts.add(EventView.ending(result) + " (" + turns + " turns)");
        }
        ToolCallSummaries.outcome(call, result).filter(o -> !result.isError()).ifPresent(facts::add);
        facts.add(String.format(Locale.ROOT, "%.1fs", took.toMillis() / MILLIS_PER_SECOND));
        List<AttributedString> out = new ArrayList<>();
        out.add(new AttributedStringBuilder().append(error ? s.fail() : s.ok(), error ? t.error() : t.ok()).append(" ")
            .append(describe(t, width)).append(s.dot() + String.join(s.dot(), facts), t.faint()).toAttributedString());
        List<AttributedString> body = body(t, false);
        int limit = fold <= 0 ? body.size() : error ? Math.max(fold, ERROR_FOLD_LINES) : fold;
        for (int i = 0; i < Math.min(limit, body.size()); i++) {
            out.add(indented(body.get(i)));
        }
        if (body.size() > limit) {
            out.add(Theme.styled(INDENT + s.more() + " (+" + (body.size() - limit) + " lines, /expand " + number + ")",
                t.faint()));
        }
        return out;
    }

    /**
     * The whole card, unfolded, with the full command of a {@code bash} call.
     *
     * @param t the colours
     * @param s the glyphs
     * @param width terminal columns
     * @return the lines
     */
    List<AttributedString> expanded(Theme t, Symbols s, int width) {
        List<AttributedString> out = new ArrayList<>(finished(t, s, Integer.MAX_VALUE, width).subList(0, 1));
        body(t, true).forEach(l -> out.add(indented(l)));
        return out;
    }

    /** A failed call, or a {@code bash} command that exited non-zero (which the tool does not count as an error). */
    boolean failed() {
        return result.isError()
            || "bash".equals(call.name()) && result.details().map(d -> d.path("exitCode").asInt(0) != 0).orElse(false);
    }

    private static AttributedString indented(AttributedString line) {
        return new AttributedStringBuilder().append(INDENT).append(line).toAttributedString();
    }

    /** Tool name in the tool colour, then its arguments in a few words. */
    private AttributedString describe(Theme t, int width) {
        String d;
        if (isSubagent()) {
            JsonNode a = call.arguments();
            d = call.name() + " " + a.path("agentType").asText("general") + ": " + a.path("task").asText();
        } else {
            d = ToolCallSummaries.describe(call);
        }
        String line = Cols.cut(TerminalInteractionImpl.visible(d), Math.max(1, width - 2 - 2 * INDENT.length()));
        int split = line.startsWith(call.name()) ? call.name().length() : line.startsWith("$ ") ? 1 : 0;
        return new AttributedStringBuilder().append(line.substring(0, split), t.tool().bold())
            .append(line.substring(split)).toAttributedString();
    }

    /** The body without indent. */
    List<AttributedString> body(Theme t, boolean full) {
        JsonNode a = call.arguments();
        String text = ToolCallSummaries.text(result);
        if (result.isError()) {
            return styledLines(EXIT_LINE.matcher(text).replaceFirst(""), t.error());
        }
        return switch (call.name()) {
            case "edit" -> DiffView.render(DiffView.edit(a.path("oldText").asText(), a.path("newText").asText(),
                firstLine(text, a.path("newText").asText())), t);
            case "write" -> DiffView.render(DiffView.edit("", a.path("content").asText(), 1), t);
            case "bash" -> {
                List<AttributedString> out = new ArrayList<>();
                if (full) {
                    out.addAll(styledLines("$ " + a.path("command").asText(), t.code()));
                }
                out.addAll(styledLines(EXIT_LINE.matcher(text).replaceFirst(""), AttributedStyle.DEFAULT));
                yield out;
            }
            default -> NO_BODY.contains(call.name()) ? List.of() : styledLines(text, AttributedStyle.DEFAULT);
        };
    }

    private static List<AttributedString> styledLines(String text, AttributedStyle style) {
        List<AttributedString> out = new ArrayList<>();
        String trimmed = text.stripTrailing();
        if (!trimmed.isEmpty()) {
            trimmed.lines().forEach(l -> out.add(Theme.styled(Cols.clean(l), style)));
        }
        return out;
    }

    /**
     * Number of the first replaced line, found in the numbered snippet ({@code N<tab>line}) that the edit tool returns;
     * 0 when the snippet does not show it.
     */
    static int firstLine(String resultText, String newText) {
        Optional<String> first = newText.lines().findFirst().map(String::stripTrailing);
        if (first.isEmpty()) {
            return 0;
        }
        for (String l : resultText.lines().toList()) {
            int tab = l.indexOf('\t');
            if (tab > 0 && l.substring(0, tab).chars().allMatch(Character::isDigit)
                && l.substring(tab + 1).stripTrailing().equals(first.get())) {
                return Integer.parseInt(l.substring(0, tab));
            }
        }
        return 0;
    }
}
