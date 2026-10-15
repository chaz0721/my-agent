/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.core.ext.SlashCommand;
import dev.pai.core.ext.UserInteraction;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ChatOptions;
import dev.pai.model.ChatRequest;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.ModelRef;
import dev.pai.model.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryExtensionTest {

    private static final ModelRef MODEL =
        new ModelRef("fake", "m", URI.create("http://fake.invalid"), 1000, 100, Set.of(ModelRef.CapabilityEnum.TOOLS));

    @TempDir
    Path home;
    @TempDir
    Path cwd;

    private final List<String> confirms = new ArrayList<>();

    private ExtensionHost host(boolean confirmAnswer) {
        UserInteraction ui = new UserInteraction() {
            @Override
            public String ask(String prompt, List<String> choices) {
                return "";
            }

            @Override
            public boolean confirm(String prompt) {
                confirms.add(prompt);
                return confirmAnswer;
            }
        };
        return new ExtensionLoader().install(List.of(new MemoryExtension()),
            new ExtensionHost.Settings(home, cwd, true, Optional.of(ui), m -> {
            }));
    }

    private static ToolResult call(ExtensionHost h, String tool, String args) throws Exception {
        Tool t = h.tools().get(tool).orElseThrow();
        return t.execute(Json.mapper().readTree(args), null);
    }

    private static String text(ToolResult r) {
        return ((ContentBlock.Text)r.content().get(0)).text();
    }

    private static String systemPrompt(ExtensionHost h, String user) {
        ChatRequest req = new ChatRequest(MODEL, Optional.of("base"), List.of(UserMessage.text(user)), List.of(),
            ChatOptions.defaults());
        return h.hooks().prepareRequest(req, null).systemPrompt().orElseThrow();
    }

    @Test
    void remember_then_a_new_session_sees_it_in_the_system_prompt() throws Exception {
        ExtensionHost first = host(true);
        assertThat(first.tools().get("remember").orElseThrow().effect()).isEqualTo(ToolEffectEnum.WRITE_FS);
        assertThat(first.tools().get("recall").orElseThrow().effect()).isEqualTo(ToolEffectEnum.READ_ONLY);
        ToolResult r = call(first, "remember", "{\"text\":\"The user prefers answers in British English\"}");
        assertThat(r.isError()).isFalse();
        ExtensionHost second = host(true);
        String prompt = systemPrompt(second, "hello there");
        assertThat(prompt).startsWith("base\n\n<memory>").contains("prefers answers in British English")
            .endsWith("</memory>");
        assertThat(text(call(second, "recall", "{\"query\":\"British\"}"))).contains("British English");
    }

    @Test
    void secrets_are_refused_and_nothing_is_stored() throws Exception {
        ExtensionHost h = host(true);
        ToolResult r = call(h, "remember", "{\"text\":\"my token is sk-abcdefghijklmnopqrstu\"}");
        assertThat(r.isError()).isTrue();
        assertThat(text(r)).contains("refused", "API key");
        assertThat(h.command("memory").orElseThrow().run("list", null).output()).isEqualTo("no memories");
    }

    @Test
    void the_four_commands_and_injection_switch() throws Exception {
        ExtensionHost h = host(true);
        call(h, "remember", "{\"text\":\"Deploys go to staging first\",\"tags\":[\"ops\"]}");
        call(h, "remember", "{\"text\":\"Use JDK 21\",\"scope\":\"project\"}");
        SlashCommand memory = h.command("memory").orElseThrow();
        String list = memory.run("list", null).output();
        assertThat(list).contains("global  Deploys go to staging first  #ops", "project  Use JDK 21");
        assertThat(memory.run("search staging", null).output()).contains("Deploys").doesNotContain("JDK");
        String id = list.substring(1, list.indexOf(']'));
        assertThat(memory.run("delete " + id, null).output()).isEqualTo("deleted " + id);
        assertThat(memory.run("delete " + id, null).output()).startsWith("no memory with id");
        assertThat(memory.run("inject off", null).output()).isEqualTo("memory injection off");
        assertThat(systemPrompt(h, "JDK")).isEqualTo("base");
        memory.run("inject on", null);
        assertThat(systemPrompt(h, "JDK")).contains("Use JDK 21");
        assertThat(memory.run("clear", null).output()).isEqualTo("deleted 1 memories");
        assertThat(confirms).hasSize(1);
        assertThat(memory.run("bogus", null).output()).isEqualTo(MemoryCommand.USAGE);
    }

    @Test
    void clear_asks_first_and_can_be_declined() throws Exception {
        ExtensionHost h = host(false);
        call(h, "remember", "{\"text\":\"keep me\"}");
        assertThat(h.command("memory").orElseThrow().run("clear", null).output()).isEqualTo("kept");
        assertThat(h.command("memory").orElseThrow().run("", null).output()).contains("keep me");
    }

    @Test
    void the_section_stays_within_its_budget() {
        MemoryStore store = new MemoryStore(home, cwd, java.time.Clock.systemUTC());
        for (int i = 0; i < 8; i++) {
            store.add(i + " " + "x".repeat(400), List.of(), MemoryStore.GLOBAL);
        }
        String s = new MemoryInjection(store).section("nothing matches").orElseThrow();
        assertThat(s.length()).isLessThanOrEqualTo(MemoryInjection.MAX_CHARS);
        assertThat(s).endsWith(MemoryInjection.CLOSE);
        assertThat(new MemoryInjection(new MemoryStore(home, home, java.time.Clock.systemUTC())).section("x"))
            .isPresent();
    }
}
