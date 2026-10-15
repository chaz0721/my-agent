/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.model.ContentBlock;
import dev.pai.model.Json;

import static org.assertj.core.api.Assertions.assertThat;

class WriteLocksTest {

    @TempDir
    Path tmp;

    private final List<String> notices = new ArrayList<>();
    private final WriteLocks locks = new WriteLocks(Duration.ofMillis(300), notices::add);
    private final Object a = new Object();
    private final Object b = new Object();

    private static ContentBlock.ToolCall call() {
        return new ContentBlock.ToolCall("same-id", "write", Json.mapper().createObjectNode());
    }

    @Test
    void a_lock_is_reentrant_for_its_owner_and_another_owner_waits_or_gives_up() {
        Path p = tmp.resolve("f");
        ContentBlock.ToolCall c1 = call();
        ContentBlock.ToolCall c2 = call();
        ContentBlock.ToolCall c3 = call();
        assertThat(locks.acquire(c1, p, a, "[A]", () -> false)).isEmpty();
        assertThat(locks.acquire(c2, p, a, "[A]", () -> false)).isEmpty();
        assertThat(locks.acquire(c3, p, b, "[B]", () -> false)).get().asString()
            .isEqualTo("write conflict: [A] is still writing " + p + "; waited 0s, try again later");
        assertThat(notices).containsExactly("[B] waits for [A] writing " + p);
        locks.release(c1);
        assertThat(locks.acquire(c3, p, b, "[B]", () -> true)).get().asString().startsWith("cancelled");
        locks.release(c2);
        locks.release(c3);
        assertThat(locks.idle()).isTrue();
        assertThat(locks.acquire(c3, p, b, "[B]", () -> false)).isEmpty();
        assertThat(locks.acquire(c1, tmp.resolve("other"), a, "[A]", () -> false)).isEmpty();
        locks.release(c3);
        locks.release(c1);
        assertThat(locks.idle()).isTrue();
    }

    @Test
    void a_run_that_ended_releases_everything_it_still_held() {
        ContentBlock.ToolCall c1 = call();
        ContentBlock.ToolCall c2 = call();
        ContentBlock.ToolCall c3 = call();
        assertThat(locks.acquire(c1, tmp.resolve("x"), a, "[A]", () -> false)).isEmpty();
        assertThat(locks.acquire(c2, tmp.resolve("y"), a, "[A]", () -> false)).isEmpty();
        assertThat(locks.acquire(c3, tmp.resolve("z"), b, "[B]", () -> false)).isEmpty();
        locks.releaseAll(a);
        assertThat(locks.acquire(call(), tmp.resolve("x"), b, "[B]", () -> false)).isEmpty();
        locks.release(c1);
        locks.releaseAll(b);
        assertThat(locks.idle()).isTrue();
    }
}
