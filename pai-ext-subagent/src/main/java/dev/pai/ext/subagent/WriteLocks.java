/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import dev.pai.model.ContentBlock;

/**
 * Write-conflict locks: one lock per real file path, held from a file-writing call's check until its result, so that
 * two agents (sub-agents and the parent) never write the same file at the same time; the later one waits. A lock
 * belongs to an agent run, not a thread (the check runs on the loop thread, the result may arrive on a worker thread),
 * and is reentrant for its owner. Only calls with a {@code path} argument are covered; {@code bash} is not.
 */
final class WriteLocks {

    /** How often a waiting call checks for cancellation. */
    private static final long POLL_MILLIS = 200L;

    private final ReentrantLock guard = new ReentrantLock();
    private final Condition released = guard.newCondition();
    private final Map<Path, Hold> holds = new HashMap<>();
    private final Map<ContentBlock.ToolCall, Path> byCall = new IdentityHashMap<>();
    private final Duration timeout;

    /** A path's owner and how many of its calls hold it. */
    private record Hold(Object owner, int count) {
    }

    WriteLocks(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Takes the lock of {@code path} for {@code call} on behalf of {@code owner}, waiting while another owner holds it.
     *
     * @return empty when taken, else why not (timeout, cancellation or interrupt)
     */
    Optional<String> acquire(ContentBlock.ToolCall call, Path path, Object owner, BooleanSupplier cancelled) {
        long left = timeout.toNanos();
        guard.lock();
        try {
            Hold h = holds.get(path);
            while (h != null && h.owner() != owner) {
                if (left <= 0L) {
                    return Optional.of("write conflict: another agent is writing " + path + "; waited "
                        + timeout.toSeconds() + "s, try again later");
                }
                if (cancelled.getAsBoolean()) {
                    return Optional.of("cancelled while waiting to write " + path);
                }
                long slice = Math.min(left, TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS));
                left -= slice - released.awaitNanos(slice);
                h = holds.get(path);
            }
            holds.put(path, new Hold(owner, h == null ? 1 : h.count() + 1));
            byCall.put(call, path);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("interrupted while waiting to write " + path);
        } finally {
            guard.unlock();
        }
    }

    /** Releases what {@code call} holds; nothing when it holds nothing. */
    void release(ContentBlock.ToolCall call) {
        guard.lock();
        try {
            Path path = byCall.remove(call);
            Hold h = path == null ? null : holds.get(path);
            if (h == null) {
                return;
            }
            if (h.count() > 1) {
                holds.put(path, new Hold(h.owner(), h.count() - 1));
            } else {
                holds.remove(path);
                released.signalAll();
            }
        } finally {
            guard.unlock();
        }
    }

    /** Whether any path is held, for tests. */
    boolean idle() {
        guard.lock();
        try {
            return holds.isEmpty();
        } finally {
            guard.unlock();
        }
    }
}
