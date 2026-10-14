/* pai-next — Apache-2.0 */
package dev.pai.model;

/**
 * Cooperative cancellation signal shared by providers, the loop and tools.
 */
public interface CancelToken {

    /**
     * Returns whether cancellation was requested.
     *
     * @return true once cancelled
     */
    boolean isCancelled();

    /**
     * Registers a callback run once on cancellation, immediately if already cancelled.
     *
     * @param r the callback
     */
    void onCancel(Runnable r);

    /**
     * Returns a token that is never cancelled.
     *
     * @return the token
     */
    static CancelToken none() {
        return CancelTokenSource.NEVER;
    }
}
