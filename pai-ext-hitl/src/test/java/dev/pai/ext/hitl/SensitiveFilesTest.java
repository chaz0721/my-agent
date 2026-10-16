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
    @ValueSource(
        strings = {".env", ".ENV", "config/.env.local", "a\\b\\.Env.prod", "certs/server.pem", "x.key", "keystore.P12",
            "id_rsa", "aws_credentials", "my-secret.txt", ".npmrc", "db/.pgpass", "vault.kdbx", "dir/.env/"})
    void looks_like_a_secret(String path) {
        assertThat(files.matches(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"README.md", "env.txt", "src/Main.java", "monkey", "keys.md", "pom.xml", "", "dir/"})
    void ordinary_files(String path) {
        assertThat(files.matches(path)).isFalse();
    }

    @Test
    void command_words_values_and_revisions_are_checked() {
        assertThat(files.inCommand("cat .env")).contains(".env");
        assertThat(files.inCommand("ls -la && head -5 config/.env.local")).contains("config/.env.local");
        assertThat(files.inCommand("git show HEAD:.env")).contains("HEAD:.env");
        assertThat(files.inCommand("grep --file=id_rsa x")).contains("--file=id_rsa");
        assertThat(files.inCommand("cat README.md")).isEmpty();
        assertThat(files.inCommand(".env")).isEmpty();
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
}
