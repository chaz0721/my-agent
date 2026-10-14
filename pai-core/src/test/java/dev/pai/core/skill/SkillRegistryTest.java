/* pai-next — Apache-2.0 */
package dev.pai.core.skill;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.ext.CommandContext;
import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.session.SessionId;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.ModelRef;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRegistryTest {

    @TempDir
    Path builtin;
    @TempDir
    Path user;
    @TempDir
    Path project;
    @TempDir
    Path home;

    private static void skill(Path root, String dir, String content) throws IOException {
        Files.createDirectories(root.resolve(dir));
        Files.writeString(root.resolve(dir).resolve("SKILL.md"), content, StandardCharsets.UTF_8);
    }

    @Test
    void later_roots_override_earlier_ones_by_name() throws IOException {
        skill(builtin, "demo", "---\nname: demo\ndescription: builtin demo\n---\nbuiltin body");
        skill(builtin, "only-builtin", "---\ndescription: from builtin\n---\nx");
        skill(user, "demo", "---\nname: demo\ndescription: user demo\n---\nuser body");
        skill(project, "demo-dir", "---\nname: demo\ndescription: project demo\n---\nproject body");
        Files.createDirectories(project.resolve("not-a-skill"));
        SkillRegistry r = new SkillRegistry(List.of(builtin, user, project, project.resolve("missing")));
        r.reload();
        assertThat(r.all()).extracting(Skill::name).containsExactly("demo", "only-builtin");
        assertThat(r.get("demo").orElseThrow().description()).isEqualTo("project demo");
        assertThat(SkillRegistry.body(r.get("demo").orElseThrow())).isEqualTo("project body");
        assertThat(r.get("only-builtin").orElseThrow().dir().getFileName()).hasToString("only-builtin");
    }

    @Test
    void frontmatter_edge_cases() {
        Frontmatter bomCrlf = Frontmatter.parse("﻿---\r\nname: \"quoted\"\r\ndescription: >\r\n  folded\r\n"
            + "  text\r\nallowed-tools:\r\n  - Read\r\n---\r\nbody line\r\n");
        assertThat(bomCrlf.values()).containsEntry("name", "quoted").containsEntry("description", "folded text");
        assertThat(bomCrlf.body()).isEqualTo("body line");
        Frontmatter none = Frontmatter.parse("# Just markdown\nno frontmatter");
        assertThat(none.values()).isEmpty();
        assertThat(none.body()).startsWith("# Just markdown");
        Frontmatter unclosed = Frontmatter.parse("---\nname: x\nbody without end");
        assertThat(unclosed.values()).isEmpty();
        Frontmatter literal = Frontmatter.parse("---\ndescription: |\n  line one\n  line two\nname: 'single'\n---\n");
        assertThat(literal.values()).containsEntry("description", "line one\nline two").containsEntry("name", "single");
        assertThat(literal.body()).isEmpty();
    }

    @Test
    void index_is_cut_by_count_and_characters() throws IOException {
        for (int i = 0; i < 5; i++) {
            skill(user, "s" + i, "---\ndescription: skill number " + i + "\n---\nbody");
        }
        SkillRegistry r = new SkillRegistry(List.of(user));
        r.reload();
        assertThat(r.indexText(20, 4000)).contains("- s0: skill number 0", "- s4: skill number 4", "load_skill");
        assertThat(r.indexText(2, 4000)).contains("- s1:").doesNotContain("- s2:").contains("(3 more skills");
        assertThat(r.indexText(20, 140).length()).isLessThan(200);
        assertThat(new SkillRegistry(List.of(project)).indexText(20, 4000)).isEmpty();
    }

    @Test
    void extension_registers_tool_prompt_section_and_commands() throws Exception {
        skill(project, "demo", "---\nname: demo\ndescription: says hello\n---\n" + "z".repeat(40_000));
        ExtensionHost host =
            new ExtensionLoader().install(List.of(new SkillsExtension(new SkillRegistry(List.of(project)))), home);
        ToolResult r = host.tools().get("load_skill").orElseThrow()
            .execute(Json.mapper().createObjectNode().put("name", "demo"), null);
        String text = ((ContentBlock.Text)r.content().get(0)).text();
        assertThat(text).startsWith("Skill directory: ").contains("[skill truncated: ");
        assertThat(text.length()).isLessThan(LoadSkillTool.MAX_BODY_BYTES + 500);
        assertThat(host.tools().get("load_skill").orElseThrow()
            .execute(Json.mapper().createObjectNode().put("name", "nope"), null).isError()).isTrue();

        ModelRef m = new ModelRef("fake", "m", URI.create("http://x.invalid"), 1000, 100, Set.of());
        ChatRequest req = new ChatRequest(m, Optional.of("base"), List.of(), List.of(), ChatOptions.defaults());
        assertThat(host.hooks().prepareRequest(req, null).systemPrompt().orElseThrow())
            .startsWith("base\n\n# Skills\n\n").contains("- demo: says hello");

        List<String> sent = new ArrayList<>();
        host.command("skill:demo").orElseThrow().run("with args", new CommandContext() {
            @Override
            public Optional<SessionId> session() {
                return Optional.empty();
            }

            @Override
            public ModelRef model() {
                return m;
            }

            @Override
            public void println(String s) {}

            @Override
            public void sendUserMessage(String t) {
                sent.add(t);
            }
        });
        assertThat(sent).containsExactly("Use skill `demo`. with args");
        assertThat(host.command("skills").orElseThrow().run("", null).output()).contains("demo", "says hello");
    }
}
