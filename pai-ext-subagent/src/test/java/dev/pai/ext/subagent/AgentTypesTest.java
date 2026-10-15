/* pai-next — Apache-2.0 */
package dev.pai.ext.subagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTypesTest {

    @TempDir
    Path tmp;

    @Test
    void built_in_types_are_general_explore_and_reviewer() {
        Map<String, AgentType> types = AgentTypes.load(tmp.resolve("none"), m -> {
        });
        assertThat(types.keySet()).containsExactly("general", "explore", "reviewer");
        assertThat(types.get("general").readOnly()).isFalse();
        assertThat(types.get("general").projectContext()).isTrue();
        assertThat(types.get("explore").readOnly()).isTrue();
        assertThat(types.get("explore").projectContext()).isFalse();
        assertThat(types.get("reviewer").readOnly()).isTrue();
        assertThat(types.get("reviewer").systemPromptAddendum()).contains("## Issues").contains("high, medium, low");
    }

    @Test
    void user_files_replace_built_in_types_add_new_ones_and_bad_ones_are_skipped() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("agents"));
        Files.writeString(dir.resolve("explore.md"),
            "﻿---\r\nname: explore\r\ndescription: \"mine\"\r\ntools: read, 'grep'\r\nmodel: deepseek-reasoner\r\n"
                + "---\r\nOnly grep.\r\n");
        Files.writeString(dir.resolve("plain.md"), "No front matter at all.");
        Files.writeString(dir.resolve("broken.md"), "---\nname: x\n");
        Files.writeString(dir.resolve("bad name.md"), "---\nname: bad name\n---\nx");
        List<String> notices = new ArrayList<>();
        Map<String, AgentType> types = AgentTypes.load(dir, notices::add);

        AgentType explore = types.get("explore");
        assertThat(explore.description()).isEqualTo("mine");
        assertThat(explore.allowedTools()).containsExactlyInAnyOrder("read", "grep");
        assertThat(explore.readOnly()).isFalse();
        assertThat(explore.model()).contains("deepseek-reasoner");
        assertThat(explore.systemPromptAddendum()).isEqualTo("Only grep.");
        AgentType plain = types.get("plain");
        assertThat(plain.systemPromptAddendum()).isEqualTo("No front matter at all.");
        assertThat(plain.allowedTools()).isEmpty();
        assertThat(plain.model()).isEqualTo(Optional.empty());
        assertThat(types).doesNotContainKeys("x", "bad name");
        assertThat(notices).hasSize(2).anyMatch(n -> n.contains("broken.md")).anyMatch(n -> n.contains("bad name"));
    }
}
