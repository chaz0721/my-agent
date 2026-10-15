/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.SessionEvent;
import dev.pai.core.session.SessionId;

import static org.assertj.core.api.Assertions.assertThat;

/** Ctrl+C at an idle prompt ends the session like /quit: SessionEnded reaches extensions before pai exits. */
class SessionEndTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private final List<String> seen = new ArrayList<>();

    private ExtensionHost host() {
        Extension recorder = new Extension() {
            @Override
            public String id() {
                return "recorder";
            }

            @Override
            public void onLoad(ExtensionContext ctx) {
                ctx.onSession(SessionEvent.SessionEnded.class, e -> seen.add("ended"));
            }

            @Override
            public void onUnload() {
                seen.add("unloaded");
            }
        };
        return new ExtensionLoader().install(List.of(recorder), ExtensionHost.Settings.of(home, cwd));
    }

    @Test
    void an_idle_interrupt_ends_the_session_once_and_then_exits() {
        Runnable end = Main.sessionEnd(() -> new SessionId("s1"), host());
        Main.onInterrupt(() -> false, end, () -> seen.add("exit"));
        end.run();
        assertThat(seen).containsExactly("ended", "unloaded", "exit");
    }

    @Test
    void an_interrupt_that_cancels_something_neither_ends_nor_exits() {
        Runnable end = Main.sessionEnd(() -> new SessionId("s1"), host());
        Main.onInterrupt(() -> true, end, () -> seen.add("exit"));
        assertThat(seen).isEmpty();
    }
}
