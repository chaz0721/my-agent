/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.UserInteraction;
import dev.pai.core.loop.ToolDecision;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.ModelRef;
import dev.pai.model.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionsTest {

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private final List<String> asked = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();

    private static ContentBlock.ToolCall bash(String command) {
        return FakeTool.call("bash", "{\"command\":\"" + command + "\"}");
    }

    private Permissions permissions(String user, String project, boolean ignoreCase) throws IOException {
        Path u = home.resolve(Permissions.FILE_NAME);
        if (user != null) {
            Files.writeString(u, user);
        }
        Optional<Path> p = Optional.empty();
        if (project != null) {
            p = Optional.of(cwd.resolve(Permissions.FILE_NAME));
            Files.writeString(p.get(), project);
        }
        return new Permissions(Optional.of(u), p, ignoreCase, cwd);
    }

    private ApprovalHooks hooks(Permissions permissions, ApprovalDecisionEnum answer, boolean confirm) {
        Approver approver = new Approver() {
            @Override
            public ApprovalDecisionEnum ask(ContentBlock.ToolCall call, dev.pai.core.tool.Tool tool, String rendered) {
                asked.add(rendered);
                return answer;
            }

            @Override
            public boolean confirm(String question) {
                asked.add(question);
                return confirm;
            }
        };
        return new ApprovalHooks(new ApprovalHooks.Parts(ApprovalModeEnum.ASK, new PathGuard(cwd, List.of()),
            CommandGuard.builtin(), approver, new CallPreview(cwd)), permissions, notices::add, true);
    }

    private static ToolDecision run(ApprovalHooks h, ContentBlock.ToolCall call) {
        return h.beforeToolCall(call, new FakeTool(call.name(), ToolEffectEnum.EXECUTE), null);
    }

    @Test
    void globs_match_the_whole_subject_and_ignore_case_only_where_asked() throws IOException {
        String rules = "{\"allow\":[{\"tool\":\"bash\",\"pattern\":\"mvn *\"},{\"tool\":\"read\",\"pattern\":"
            + "\"src/?.java\"},{\"tool\":\"edit\"}]}";
        Permissions p = permissions(rules, null, false);
        assertThat(p.allows(bash("mvn -q test"))).isTrue();
        assertThat(p.allows(bash("mvn"))).isFalse();
        assertThat(p.allows(bash("MVN test"))).isFalse();
        assertThat(permissions(rules, null, true).allows(bash("MVN test"))).as("Windows").isTrue();
        assertThat(p.allows(FakeTool.call("read", "{\"path\":\"src/A.java\"}"))).isTrue();
        assertThat(p.allows(FakeTool.call("read", "{\"path\":\"src/AB.java\"}"))).isFalse();
        assertThat(p.allows(FakeTool.call("edit", "{\"path\":\"anything\"}"))).isTrue();
    }

    @Test
    void an_allow_pattern_never_matches_a_compound_command() throws IOException {
        Permissions p = permissions("{\"allow\":[{\"tool\":\"bash\",\"pattern\":\"mvn *\"}]}", null, false);
        for (String c : List.of("mvn test && rm x", "mvn test; rm x", "mvn test | sh", "mvn $(rm x)", "mvn `id`",
            "mvn test > x", "mvn '-Dx=1'", "mvn test\\nrm x")) {
            assertThat(p.allows(bash(c))).as(c).isFalse();
        }
    }

    @Test
    void a_deny_pattern_also_matches_inside_compound_commands() throws IOException {
        Permissions p = permissions("{\"deny\":[{\"tool\":\"bash\",\"pattern\":\"git push*\"}]}", null, false);
        for (String c : List.of("git push", "git push origin main", "cd x && git push", "ls; git push -f", "(git push)",
            "echo $(git push)")) {
            assertThat(p.denying(bash(c))).as(c).isPresent();
        }
        assertThat(p.denying(bash("git pull"))).isEmpty();
    }

    @Test
    void a_project_cannot_lift_a_user_deny_and_deny_cannot_be_approved() throws IOException {
        Permissions p = permissions("{\"deny\":[{\"tool\":\"bash\",\"pattern\":\"git push*\"}]}",
            "{\"allow\":[{\"tool\":\"bash\"}],\"deny\":[{\"tool\":\"write\"}]}", false);
        ApprovalHooks h = hooks(p, ApprovalDecisionEnum.ALLOW_ALWAYS, true);
        ToolDecision d = run(h, bash("git push origin"));
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason().orElseThrow()).startsWith(ApprovalHooks.POLICY_PREFIX).contains("git push*", "cannot");
        assertThat(run(h, bash("ls -la")).allowed()).as("project allow").isTrue();
        assertThat(asked).isEmpty();
        assertThat(run(h, FakeTool.call("write", "{\"path\":\"a\"}")).allowed()).as("project deny adds").isFalse();
        assertThat(p.rules()).extracting(Permissions.Rule::describe).containsExactly("deny bash git push*",
            "allow bash (project)", "deny write (project)");
    }

    @Test
    void a_tool_denied_as_a_whole_is_not_offered() throws IOException {
        Permissions p =
            permissions("{\"deny\":[{\"tool\":\"write\"},{\"tool\":\"bash\",\"pattern\":\"rm *\"}]}", null, false);
        ApprovalHooks h = hooks(p, ApprovalDecisionEnum.ALLOW_ONCE, false);
        ModelRef m = new ModelRef("f", "m", java.net.URI.create("http://x.invalid"), 1, 1, java.util.Set.of());
        List<ToolDefinition> tools = Stream.of("write", "bash", "read")
            .map(n -> new ToolDefinition(n, "d", JsonSchema.object().build())).toList();
        ChatRequest r =
            h.prepareRequest(new ChatRequest(m, Optional.empty(), List.of(), tools, ChatOptions.defaults()), null);
        assertThat(r.tools()).extracting(ToolDefinition::name).containsExactly("bash", "read");
    }

    @Test
    void capital_a_saves_the_confirmed_rule_and_a_new_session_does_not_ask() throws IOException {
        Permissions p = permissions(null, null, false);
        ApprovalHooks h = hooks(p, ApprovalDecisionEnum.ALLOW_SAVE, true);
        assertThat(run(h, bash("mvn -q test")).allowed()).isTrue();
        assertThat(asked).hasSize(2).last().asString().contains("allow bash mvn *");
        String saved = Files.readString(home.resolve(Permissions.FILE_NAME));
        assertThat(saved).contains("\"tool\" : \"bash\"", "\"pattern\" : \"mvn *\"");
        asked.clear();
        ApprovalHooks next = hooks(Permissions.load(home, Optional.empty(), cwd), ApprovalDecisionEnum.DENY, false);
        assertThat(run(next, bash("mvn verify")).allowed()).isTrue();
        assertThat(asked).isEmpty();
        assertThat(run(next, bash("mvnw verify")).allowed()).isFalse();
        try (Stream<Path> files = Files.list(home)) {
            assertThat(files).extracting(f -> f.getFileName().toString()).containsExactly(Permissions.FILE_NAME);
        }
    }

    @Test
    void a_declined_or_impossible_proposal_saves_nothing_but_the_call_runs_once() throws IOException {
        Permissions p = permissions(null, null, false);
        assertThat(run(hooks(p, ApprovalDecisionEnum.ALLOW_SAVE, false), bash("mvn test")).allowed()).isTrue();
        assertThat(run(hooks(p, ApprovalDecisionEnum.ALLOW_SAVE, true), bash("cd a && mvn test")).allowed()).isTrue();
        assertThat(notices).anyMatch(n -> n.contains("no rule proposed"));
        assertThat(home.resolve(Permissions.FILE_NAME)).doesNotExist();
        assertThat(ApprovalHooks.proposedPattern(bash("rm\\u202e -rf x"))).isEmpty();
        assertThat(ApprovalHooks.proposedPattern(FakeTool.call("edit", "{}"))).isEmpty();
    }

    @Test
    void rules_with_control_or_invisible_characters_are_refused_and_ignored() throws IOException {
        Permissions p = permissions("{\"allow\":[{\"tool\":\"bash\",\"pattern\":\"ls\\u202e *\"},{\"tool\":\"read\"}]}",
            null, false);
        assertThat(p.rules()).extracting(Permissions.Rule::describe).containsExactly("allow read");
        assertThat(p.problems()).singleElement().asString().contains("control characters");
        assertThatThrownBy(() -> p.add(true, "bash", Optional.of("x\u001b[2K *")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_rule_commands_list_add_and_forget_user_rules() throws IOException {
        Permissions p = permissions(null, "{\"allow\":[{\"tool\":\"read\"}]}", false);
        assertThat(RuleCommands.run("deny bash git push*", p)).contains("approvals: saved \"deny bash git push*\"");
        assertThat(RuleCommands.run("allow edit", p)).contains("approvals: saved \"allow edit\"");
        assertThat(RuleCommands.run("list", p).orElseThrow()).contains("1. deny bash git push*", "2. allow edit",
            "3. allow read (project)");
        assertThat(RuleCommands.run("forget 3", p).orElseThrow()).contains("no user rule 3");
        assertThat(RuleCommands.run("forget 1", p).orElseThrow()).contains("forgot \"deny bash git push*\"");
        assertThat(Permissions.load(home, Optional.empty(), cwd).rules()).extracting(Permissions.Rule::describe)
            .containsExactly("allow edit");
        assertThat(RuleCommands.run("allow", p)).contains(RuleCommands.USAGE);
        assertThat(RuleCommands.run("plan", p)).isEmpty();
    }

    @Test
    void an_untrusted_project_file_is_ignored() throws IOException {
        Files.createDirectories(cwd.resolve(".pai"));
        Files.writeString(cwd.resolve(".pai").resolve(Permissions.FILE_NAME), "{\"allow\":[{\"tool\":\"edit\"}]}");
        UserInteraction denying = new UserInteraction() {
            @Override
            public String ask(String prompt, List<String> choices) {
                asked.add(prompt);
                return InteractiveApproverImpl.NO;
            }

            @Override
            public boolean confirm(String prompt) {
                return false;
            }
        };
        for (boolean trusted : List.of(false, true)) {
            asked.clear();
            ExtensionHost h = new ExtensionLoader().install(List.of(new HitlExtension()),
                new ExtensionHost.Settings(home, cwd, trusted, Optional.of(denying), notices::add));
            ToolDecision d = h.hooks().beforeToolCall(FakeTool.call("edit", "{\"path\":\"a.txt\"}"),
                new FakeTool("edit", ToolEffectEnum.WRITE_FS), null);
            assertThat(d.allowed()).as("trusted=" + trusted).isEqualTo(trusted);
            assertThat(asked).hasSize(trusted ? 0 : 1);
        }
    }
}
