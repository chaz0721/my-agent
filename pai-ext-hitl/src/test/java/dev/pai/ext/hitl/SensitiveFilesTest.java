/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.pai.core.loop.ToolDecision;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;

import static org.assertj.core.api.Assertions.assertThat;

/** M8.5D: read-only calls naming a file that looks like a secret are asked about. */
class SensitiveFilesTest {

    @TempDir
    Path cwd;

    private final List<String> asked = new ArrayList<>();
    private final SensitiveFiles files = SensitiveFiles.builtin();

    private ApprovalHooks hooks(ApprovalModeEnum mode, ApprovalDecisionEnum answer) {
        return new ApprovalHooks(new ApprovalHooks.Parts(mode, new PathGuard(cwd, List.of()), CommandGuard.builtin(),
            (call, tool, rendered) -> {
                asked.add(rendered);
                return answer;
            }, new CallPreview(cwd)));
    }

    private ToolDecision bash(ApprovalHooks h, String command) {
        return h.beforeToolCall(FakeTool.call("bash", "{\"command\":\"" + command + "\"}"), new FakeBashTool(), null);
    }

    private ToolDecision read(ApprovalHooks h, String path) {
        Tool read = new FakeTool("read", ToolEffectEnum.READ_ONLY);
        return h.beforeToolCall(FakeTool.call("read", "{\"path\":\"" + path + "\"}"), read, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {".env", ".ENV", "config/.env.local", "a\\b\\.Env.prod", "certs/server.pem", "x.key",
        "keystore.P12", "id_rsa", "aws_credentials", "my-secret.txt", ".npmrc", "db/.pgpass", "vault.kdbx", "dir/.env/",
        "x.pem.", "x.pem...", ".npmrc.", "x.key . ", "id_ed25519.pub"})
    void looks_like_a_secret(String path) {
        assertThat(files.matches(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"README.md", "env.txt", "src/Main.java", "monkey", "keys.md", "pom.xml", "", "dir/",
        "id_utils.py", ".env.example", "config/.ENV.Sample", ".env.template"})
    void ordinary_files(String path) {
        assertThat(files.matches(path)).isFalse();
    }

    @Test
    void command_words_values_and_revisions_are_checked() {
        assertThat(files.inCommand("cat .env", cwd)).contains(".env");
        assertThat(files.inCommand("ls -la && head -5 config/.env.local", cwd)).contains("config/.env.local");
        assertThat(files.inCommand("git show HEAD:.env", cwd)).contains("HEAD:.env");
        assertThat(files.inCommand("grep --file=id_rsa x", cwd)).contains("--file=id_rsa");
        assertThat(files.inCommand("cat README.md", cwd)).isEmpty();
        assertThat(files.inCommand(".env", cwd)).isEmpty();
    }

    @Test
    void cat_env_is_asked_in_auto_read_and_denied_on_n() {
        ToolDecision d = bash(hooks(ApprovalModeEnum.AUTO_READ, ApprovalDecisionEnum.DENY), "cat .env");
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains(ApprovalHooks.DENIED);
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0)).contains("sensitive file").contains(".env");
    }

    @Test
    void read_env_is_asked_and_runs_on_yes_without_remembering() {
        ApprovalHooks h = hooks(ApprovalModeEnum.AUTO_READ, ApprovalDecisionEnum.ALLOW_ALWAYS);
        assertThat(read(h, "config/.env.local").allowed()).isTrue();
        assertThat(read(h, "CONFIG/.ENV.LOCAL").allowed()).isTrue();
        assertThat(asked).hasSize(2).allSatisfy(r -> assertThat(r).contains("sensitive file"));
        assertThat(h.alwaysAllowed()).isEmpty();
    }

    @Test
    void ordinary_reads_stay_free_and_plan_asks_too() {
        ApprovalHooks h = hooks(ApprovalModeEnum.AUTO_READ, ApprovalDecisionEnum.DENY);
        assertThat(bash(h, "cat README.md").allowed()).isTrue();
        assertThat(read(h, "README.md").allowed()).isTrue();
        assertThat(asked).isEmpty();
        assertThat(read(hooks(ApprovalModeEnum.PLAN, ApprovalDecisionEnum.DENY), ".env").allowed()).isFalse();
        assertThat(asked).hasSize(1);
    }

    @Test
    void yolo_is_not_affected() {
        ApprovalHooks h = hooks(ApprovalModeEnum.YOLO, ApprovalDecisionEnum.DENY);
        assertThat(bash(h, "cat .env").allowed()).isTrue();
        assertThat(read(h, ".env").allowed()).isTrue();
        assertThat(asked).isEmpty();
    }

    /** spawn_agent stand-in: read-only for explore, else it executes. */
    private static final Tool SPAWN = new Tool() {
        @Override
        public dev.pai.model.ToolDefinition definition() {
            return new dev.pai.model.ToolDefinition("spawn_agent", "fake",
                dev.pai.core.tool.JsonSchema.object().build());
        }

        @Override
        public ToolEffectEnum effect() {
            return ToolEffectEnum.EXECUTE;
        }

        @Override
        public ToolEffectEnum effect(com.fasterxml.jackson.databind.JsonNode input) {
            return "explore".equals(input.path("agentType").asText()) ? ToolEffectEnum.READ_ONLY
                : ToolEffectEnum.EXECUTE;
        }

        @Override
        public dev.pai.core.tool.ToolResult execute(com.fasterxml.jackson.databind.JsonNode a,
            dev.pai.core.tool.ToolContext c) {
            return dev.pai.core.tool.ToolResult.text("ran");
        }
    };

    @Test
    void a_read_only_call_without_a_command_runs_like_a_read_only_tool() {
        String explore = "{\"task\":\"x\",\"agentType\":\"explore\"}";
        for (ApprovalModeEnum mode : List.of(ApprovalModeEnum.AUTO_READ, ApprovalModeEnum.PLAN)) {
            ApprovalHooks h = hooks(mode, ApprovalDecisionEnum.DENY);
            assertThat(h.beforeToolCall(FakeTool.call("spawn_agent", explore), SPAWN, null).allowed()).isTrue();
        }
        assertThat(asked).isEmpty();
        ApprovalHooks h = hooks(ApprovalModeEnum.AUTO_READ, ApprovalDecisionEnum.DENY);
        String general = "{\"task\":\"x\",\"agentType\":\"general\"}";
        assertThat(h.beforeToolCall(FakeTool.call("spawn_agent", general), SPAWN, null).allowed()).isFalse();
        assertThat(asked).hasSize(1);
    }

    @Test
    void a_link_is_checked_by_the_name_of_its_target() throws java.io.IOException {
        java.nio.file.Files.writeString(cwd.resolve(".env"), "DUMMY=1\n");
        try {
            java.nio.file.Files.createSymbolicLink(cwd.resolve("notes"), cwd.resolve(".env"));
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links not available: " + e.getMessage());
        }
        assertThat(files.matches("notes")).isFalse();
        assertThat(files.matches("notes", cwd)).isTrue();
        assertThat(files.inCommand("cat notes", cwd)).contains("notes");
        ApprovalHooks h = hooks(ApprovalModeEnum.AUTO_READ, ApprovalDecisionEnum.DENY);
        assertThat(read(h, "notes").allowed()).isFalse();
        assertThat(bash(h, "cat notes").allowed()).isFalse();
        assertThat(asked).hasSize(2).allSatisfy(r -> assertThat(r).contains("sensitive file"));
    }

    @Test
    void trailing_dots_are_asked_about_through_the_hooks() {
        ApprovalHooks h = hooks(ApprovalModeEnum.AUTO_READ, ApprovalDecisionEnum.DENY);
        for (String p : List.of("x.pem.", "x.pem...", ".npmrc.")) {
            assertThat(read(h, p).allowed()).isFalse();
        }
        assertThat(bash(h, "cat x.pem.").allowed()).isFalse();
        assertThat(asked).hasSize(4);
    }

    @Test
    void without_the_resource_a_minimal_list_applies() {
        SensitiveFiles fallback = SensitiveFiles.builtin(null);
        assertThat(fallback.matches(".env")).isTrue();
        assertThat(fallback.matches("server.pem")).isTrue();
        assertThat(fallback.matches("id_rsa")).isTrue();
        assertThat(fallback.matches("README.md")).isFalse();
        java.io.InputStream broken = new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("broken");
            }
        };
        assertThat(SensitiveFiles.builtin(broken).matches("x.key")).isTrue();
    }

    @Test
    void exclusions_win_over_globs() {
        SensitiveFiles s = SensitiveFiles.of(List.of("# c", ".env*", "!.env.example"));
        assertThat(s.matches(".env.local")).isTrue();
        assertThat(s.matches(".ENV.EXAMPLE")).isFalse();
    }
}
