/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.Console;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * Chooses the renderer: JLine on an interactive terminal whose {@code TERM} is not {@code dumb}, plain lines otherwise
 * (also when JLine cannot open the system terminal). The JLine renderer's colours follow {@link Theme#THEME_ENV} and
 * {@link Theme#NO_COLOR_ENV}; {@value #FOLD_ENV}{@code =0} turns folding of tool output off, a positive number sets the
 * lines shown.
 */
public final class RendererFactory {

    /** Environment variable setting how many lines of tool output a card shows; 0 shows all. */
    public static final String FOLD_ENV = "PAI_TUI_FOLD";

    private RendererFactory() {}

    /**
     * Creates the renderer for the current process.
     *
     * @return the renderer
     */
    public static Renderer create() {
        Console console = System.console();
        if (useTerminalUi(isTerminal(console), System.getenv())) {
            try {
                return JLineRendererImpl.system();
            } catch (UncheckedIOException e) {
                System.err.println("pai: terminal UI unavailable (" + e.getCause().getMessage()
                    + "); using plain output. On JDK 21 start java with --enable-native-access=ALL-UNNAMED");
            }
        }
        Charset in = console != null ? console.charset() : Charset.defaultCharset();
        return new PlainRendererImpl(System.in, in, System.out);
    }

    /**
     * Reads the folding of tool cards from the environment.
     *
     * @param env the environment
     * @return lines shown per card, 0 for all; {@link ToolCard#FOLD_LINES} when unset, negative or not a number
     */
    static int fold(Map<String, String> env) {
        try {
            int n = Integer.parseInt(env.getOrDefault(FOLD_ENV, "").strip());
            return n < 0 ? ToolCard.FOLD_LINES : n;
        } catch (NumberFormatException e) {
            return ToolCard.FOLD_LINES;
        }
    }

    /**
     * Returns whether the console is an interactive terminal. JDK 22+ returns a console even when the streams are
     * redirected and answers through {@code Console.isTerminal()}; JDK 21 has no such method and returns no console
     * when redirected.
     *
     * @param console the console, may be null
     * @return true for an interactive terminal
     */
    static boolean isTerminal(Console console) {
        if (console == null) {
            return false;
        }
        try {
            return (Boolean)Console.class.getMethod("isTerminal").invoke(console);
        } catch (NoSuchMethodException e) {
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * Returns whether the JLine renderer applies.
     *
     * @param interactive whether a console is attached
     * @param env the environment
     * @return true for JLine
     */
    static boolean useTerminalUi(boolean interactive, Map<String, String> env) {
        return interactive && !"dumb".equals(env.get("TERM"));
    }
}
