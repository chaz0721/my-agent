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
import dev.pai.core.ext.SlashCommand;
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
    void settings_come_from_the_host_option_then_the_file() throws IOException {
        Files.writeString(home.resolve(HitlSettings.FILE_NAME), "{\"mode\":\"auto-read\",\"allowDirs\":[\"/tmp/x\"]}");
        assertThat(HitlSettings.load(home, Optional.empty()).mode()).isEqualTo(ApprovalModeEnum.AUTO_READ);
        assertThat(HitlSettings.load(home, Optional.empty()).allowDirs()).containsExactly(Path.of("/tmp/x"));
        assertThat(HitlSettings.load(home, Optional.of("YOLO")).mode()).isEqualTo(ApprovalModeEnum.YOLO);
        assertThat(HitlSettings.load(home, Optional.of("plan")).mode()).isEqualTo(ApprovalModeEnum.PLAN);
        HitlSettings bad = HitlSettings.load(home, Optional.of("sometimes"));
        assertThat(bad.mode()).isEqualTo(ApprovalModeEnum.AUTO_READ);
        assertThat(bad.problems()).singleElement().satisfies(p -> assertThat(p).contains("sometimes"));
        assertThat(HitlSettings.load(cwd, Optional.empty()).mode()).isEqualTo(ApprovalModeEnum.AUTO_READ);
    }

    @Test
    void the_approval_option_reaches_the_extension_without_a_system_property() {
        ExtensionHost h = new ExtensionLoader().install(List.of(new HitlExtension()),
            new ExtensionHost.Settings(home, cwd, true, Optional.empty(), notices::add)
                .withOptions(Map.of(HitlSettings.MODE_OPTION, "ask")));
        assertThat(h.command("approvals").orElseThrow().run("", null).output()).contains("mode ask");
        assertThat(System.getProperty("pai.approval")).isNull();
    }

    @Test
    void shift_tab_cycles_ask_auto_read_plan_and_never_reaches_yolo() {
        ExtensionHost h = new ExtensionLoader().install(List.of(new HitlExtension()),
            new ExtensionHost.Settings(home, cwd, true, Optional.empty(), notices::add)
                .withOptions(Map.of(HitlSettings.MODE_OPTION, "yolo")));
        SlashCommand approvals = h.command("approvals").orElseThrow();
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seen.add(approvals.run("cycle", null).output());
        }
        assertThat(seen).containsExactly("approvals: mode ask", "approvals: mode auto-read", "approvals: mode plan",
            "approvals: mode ask");
        assertThat(approvals.run("yolo", null).output()).isEqualTo(ModeCommands.YOLO_REFUSED);
        assertThat(approvals.run("", null).output()).contains("mode ask");
    }

    @Test
    void plan_mode_blocks_writes_until_it_is_left() {
        ExtensionHost h = host(Optional.of(answering("y")));
        SlashCommand plan = h.command("plan").orElseThrow();
        assertThat(plan.run("", null).output()).startsWith("plan mode on");
        ToolDecision blocked = edit(h);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.reason()).contains(ApprovalHooks.PLAN_BLOCK);
        assertThat(prompts).isEmpty();
        assertThat(plan.run("off", null).output()).contains("mode auto-read");
        assertThat(edit(h).allowed()).isTrue();
    }
}
