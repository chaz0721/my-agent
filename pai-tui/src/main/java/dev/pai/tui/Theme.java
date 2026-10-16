/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.util.Locale;
import java.util.Map;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/**
 * Semantic colours of the terminal UI, one set for dark and one for light backgrounds, in the 16 basic ANSI colours
 * only (Windows conhost renders neither true colour nor reliably 256 colours). {@link #NONE} means no colour at all:
 * the renderer then keeps the uncoloured M8.5 behaviour.
 *
 * @param name {@code dark}, {@code light} or {@code none}
 * @param heading Markdown headings
 * @param emphasis bold text and the selected choice
 * @param code inline code and code blocks
 * @param tool tool names in cards
 * @param ok success marks and added diff lines
 * @param warn notices
 * @param error failures and removed diff lines
 * @param faint secondary text: reasoning, context lines, hints
 * @param spinner frames of the busy indicator, one character each
 */
public record Theme(String name, AttributedStyle heading, AttributedStyle emphasis, AttributedStyle code,
    AttributedStyle tool, AttributedStyle ok, AttributedStyle warn, AttributedStyle error, AttributedStyle faint,
    String spinner) {

    /** Environment variable choosing the theme: {@code dark}, {@code light} or {@code none}. */
    public static final String THEME_ENV = "PAI_THEME";
    /** The informal standard variable that turns colour off when set to anything non-empty. */
    public static final String NO_COLOR_ENV = "NO_COLOR";
    /** Spinner frames for consoles whose fonts may lack Braille patterns. */
    static final String ASCII_SPINNER = "|/-\\";
    private static final String BRAILLE_SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";
    private static final int MIN_COLORS = 8;
    private static final AttributedStyle PLAIN = AttributedStyle.DEFAULT;

    /** No colour: the renderer behaves as in M8.5. */
    public static final Theme NONE =
        new Theme("none", PLAIN, PLAIN, PLAIN, PLAIN, PLAIN, PLAIN, PLAIN, PLAIN, ASCII_SPINNER);
    /** Colours for dark backgrounds. */
    public static final Theme DARK = new Theme("dark", fg(AttributedStyle.CYAN | AttributedStyle.BRIGHT).bold(),
        PLAIN.bold(), fg(AttributedStyle.YELLOW), fg(AttributedStyle.CYAN), fg(AttributedStyle.GREEN),
        fg(AttributedStyle.YELLOW), fg(AttributedStyle.RED | AttributedStyle.BRIGHT),
        fg(AttributedStyle.BLACK | AttributedStyle.BRIGHT), BRAILLE_SPINNER);
    /** Colours for light backgrounds. */
    public static final Theme LIGHT = new Theme("light", fg(AttributedStyle.BLUE).bold(), PLAIN.bold(),
        fg(AttributedStyle.MAGENTA), fg(AttributedStyle.BLUE), fg(AttributedStyle.GREEN), fg(AttributedStyle.MAGENTA),
        fg(AttributedStyle.RED), fg(AttributedStyle.BLACK | AttributedStyle.BRIGHT), BRAILLE_SPINNER);

    /**
     * Returns whether this theme colours anything.
     *
     * @return false for {@link #NONE}
     */
    public boolean color() {
        return !NONE.name().equals(name);
    }

    /**
     * Returns a copy with other spinner frames.
     *
     * @param frames the frames
     * @return the copy
     */
    Theme withSpinner(String frames) {
        return new Theme(name, heading, emphasis, code, tool, ok, warn, error, faint, frames);
    }

    /**
     * Chooses the theme: {@value #THEME_ENV} when it names one; otherwise none when {@value #NO_COLOR_ENV} is set or
     * the terminal has fewer than 8 colours (dumb), else dark. A Windows console that is not Windows Terminal (no
     * {@code WT_SESSION}, no {@code TERM}: conhost) gets the ASCII spinner, since its default fonts lack Braille.
     *
     * @param env the environment
     * @param terminalType the terminal type, for example {@code xterm-256color} or {@code dumb}
     * @param colors colours the terminal reports, null when unknown
     * @param windows whether the OS is Windows
     * @return the theme
     */
    static Theme detect(Map<String, String> env, String terminalType, Integer colors, boolean windows) {
        String asked = env.getOrDefault(THEME_ENV, "").strip().toLowerCase(Locale.ROOT);
        Theme t = switch (asked) {
            case "none" -> NONE;
            case "light" -> LIGHT;
            case "dark" -> DARK;
            default -> {
                boolean noColor = !env.getOrDefault(NO_COLOR_ENV, "").isEmpty();
                boolean dumb =
                    terminalType == null || terminalType.startsWith("dumb") || colors != null && colors < MIN_COLORS;
                yield noColor || dumb ? NONE : DARK;
            }
        };
        boolean conhost = windows && !env.containsKey("WT_SESSION") && !env.containsKey("TERM");
        return conhost && t.color() ? t.withSpinner(ASCII_SPINNER) : t;
    }

    /**
     * Styles a text.
     *
     * @param text the text
     * @param style one of this theme's styles
     * @return the styled text
     */
    static AttributedString styled(String text, AttributedStyle style) {
        return new AttributedString(text, style);
    }

    private static AttributedStyle fg(int color) {
        return AttributedStyle.DEFAULT.foreground(color);
    }
}
