/* pai-next — Apache-2.0 */
package dev.pai.ext.verify;

import java.util.Optional;
import java.util.function.Consumer;

import dev.pai.core.ext.UserInteraction;

/**
 * Asks once per session before the first automatic verification run. A refusal switches automatic runs off for the
 * session; without a terminal nobody can agree, so the user is told once and nothing runs automatically.
 */
final class VerifyConsent {

    /** Whether automatic runs may happen. */
    private enum StateEnum {
        /** Not asked yet in this session. */
        UNASKED,
        /** The user agreed. */
        ALLOWED,
        /** The user refused, or nobody could be asked. */
        REFUSED
    }

    private final Optional<UserInteraction> ui;
    private final Consumer<String> notifier;
    private StateEnum state = StateEnum.UNASKED;

    VerifyConsent(Optional<UserInteraction> ui, Consumer<String> notifier) {
        this.ui = ui;
        this.notifier = notifier;
    }

    /**
     * Tells whether automatic runs are allowed, asking on first use.
     *
     * @param commands the commands that would run, for the question
     * @return true when the user agreed this session
     */
    synchronized boolean allowed(String commands) {
        if (state == StateEnum.UNASKED) {
            if (ui.isEmpty()) {
                notifier.accept("verify: would run " + commands + " after writes, but nobody can confirm; "
                    + "automatic runs are off (run /verify to check)");
                state = StateEnum.REFUSED;
            } else {
                boolean yes = ui.get().confirm("verify will run " + commands + " after writes this session, allow?");
                state = yes ? StateEnum.ALLOWED : StateEnum.REFUSED;
                if (!yes) {
                    notifier.accept("verify: automatic runs off for this session (run /verify to check)");
                }
            }
        }
        return state == StateEnum.ALLOWED;
    }

    /** A new session asks again. */
    synchronized void reset() {
        state = StateEnum.UNASKED;
    }
}
