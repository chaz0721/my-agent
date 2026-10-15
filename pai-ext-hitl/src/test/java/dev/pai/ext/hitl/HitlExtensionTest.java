/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.UserInteraction;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.tool.ToolEffectEnum;

import static org.assertj.core.api.Assertions.assertThat;

class HitlExtensionTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private final List<String> notices = new ArrayList<>();
    private final List<String> prompts = new ArrayList<>();

    private ExtensionHost host(Optional<UserInteraction> ui) {
        return new ExtensionLoader().install(List.of(new HitlExtension()),
            new ExtensionHost.Settings(home, cwd, true, ui, notices::add));
    }

    private UserInteraction answering(String answer) {
        return new UserInteraction() {
            @Override
            public String ask(String prompt, List<String> choices) {
                prompts.add(prompt);
                return answer;
            }

            @Override
            public boolean confirm(String prompt) {
                return false;
            }
        };
    }

    private static ToolDecision edit(ExtensionHost h) {
        return h.hooks().beforeToolCall(
            FakeTool.call("edit", "{\"path\":\"a.txt\",\"oldText\":\"a\",\"newText\":\"b\"}"),
            new FakeTool("edit", ToolEffectEnum.WRITE_FS), null);
    }

    @Test
    void without_a_terminal_calls_are_denied_and_the_user_is_told() {
        ExtensionHost h = host(Optional.empty());
        assertThat(edit(h).allowed()).isFalse();
        assertThat(notices).anyMatch(n -> n.contains("no terminal to ask"));
        assertThat(h.command("approvals")).isPresent();
    }

    @Test
    void the_user_is_asked_through_the_interaction_and_always_sticks() {
        ExtensionHost h = host(Optional.of(answering("a")));
        assertThat(edit(h).allowed()).isTrue();
        assertThat(edit(h).allowed()).isTrue();
        assertThat(prompts).singleElement().satisfies(p -> assertThat(p).contains("tool edit", "a = always"));
        assertThat(h.command("approvals").orElseThrow().run("", null).output()).contains("mode auto-read", "edit",
            HitlExtension.SCOPE_NOTE);
        h.command("approvals").orElseThrow().run("reset", null);
        assertThat(h.command("approvals").orElseThrow().run("", null).output()).contains("none");
    }

    @Test
    void interrupted_questions_count_as_no_answer() {
        assertThat(edit(host(Optional.of(answering("")))).reason()).contains(ApprovalHooks.NO_ANSWER);
    }

    @Test
    void settings_come_from_property_then_environment_then_file() throws IOException {
        Files.writeString(home.resolve(HitlSettings.FILE_NAME), "{\"mode\":\"auto-read\",\"allowDirs\":[\"/tmp/x\"]}");
        assertThat(HitlSettings.load(home, Optional.empty(), Map.of()).mode()).isEqualTo(ApprovalModeEnum.AUTO_READ);
        assertThat(HitlSettings.load(home, Optional.empty(), Map.of()).allowDirs()).containsExactly(Path.of("/tmp/x"));
        assertThat(HitlSettings.load(home, Optional.empty(), Map.of(HitlSettings.MODE_ENV, "YOLO")).mode())
            .isEqualTo(ApprovalModeEnum.YOLO);
        assertThat(HitlSettings.load(home, Optional.of("ask"), Map.of(HitlSettings.MODE_ENV, "yolo")).mode())
            .isEqualTo(ApprovalModeEnum.ASK);
        HitlSettings bad = HitlSettings.load(home, Optional.of("sometimes"), Map.of());
        assertThat(bad.mode()).isEqualTo(ApprovalModeEnum.AUTO_READ);
        assertThat(bad.problems()).singleElement().satisfies(p -> assertThat(p).contains("sometimes"));
        assertThat(HitlSettings.load(cwd, Optional.empty(), Map.of()).mode()).isEqualTo(ApprovalModeEnum.AUTO_READ);
    }
}
