/* pai-next — Apache-2.0 */
package dev.pai.tui;

import java.io.Console;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * Chooses the renderer: JLine on an interactive terminal whose {@code TERM} is not {@code dumb}, plain lines otherwise.
 */
public final class RendererFactory {

    private RendererFactory() {}

    /**
     * Creates the renderer for the current process.
     *
     * @return the renderer
     */
    public static Renderer create() {
        Console console = System.console();
        if (useTerminalUi(console != null, System.getenv())) {
            return JLineRendererImpl.system();
        }
        Charset in = console != null ? console.charset() : Charset.defaultCharset();
        return new PlainRendererImpl(System.in, in, System.out);
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
