/* pai-next — Apache-2.0 */
package dev.pai.cli;

import sun.misc.Signal;

/**
 * Ctrl+C handling: the first interrupt cancels the running request, an interrupt while idle exits.
 */
final class Interrupts {

    private Interrupts() {}

    static void install(Repl repl, int exitCode) {
        try {
            Signal.handle(new Signal("INT"), sig -> {
                if (!repl.interrupt()) {
                    System.exit(exitCode);
                }
            });
        } catch (IllegalArgumentException e) {
            // signal not supported on this platform: default handling (exit) applies
        }
    }
}
