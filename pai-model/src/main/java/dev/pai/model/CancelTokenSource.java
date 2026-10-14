/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link CancelToken} that its owner can cancel.
 */
public final class CancelTokenSource implements CancelToken {

    static final CancelToken NEVER = new CancelToken() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void onCancel(Runnable r) {
            // never cancelled: callbacks are never run
        }
    };

    private static final Logger log = LoggerFactory.getLogger(CancelTokenSource.class);

    private final List<Runnable> callbacks = new ArrayList<>();
    private boolean cancelled;

    /**
     * Requests cancellation and runs registered callbacks once.
     */
    public void cancel() {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            toRun = List.copyOf(callbacks);
            callbacks.clear();
        }
        toRun.forEach(CancelTokenSource::runQuietly);
    }

    @Override
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void onCancel(Runnable r) {
        synchronized (this) {
            if (!cancelled) {
                callbacks.add(r);
                return;
            }
        }
        runQuietly(r);
    }

    private static void runQuietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("cancel callback failed", e);
        }
    }
}
