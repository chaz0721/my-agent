/* pai-next — Apache-2.0 */
package dev.pai.core.skill;

import java.util.stream.Collectors;

import dev.pai.core.ext.CommandContext;
import dev.pai.core.ext.CommandResult;
import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;
import dev.pai.core.ext.SlashCommand;
import dev.pai.core.loop.AgentHooks;
import dev.pai.core.loop.LoopState;
import dev.pai.core.prompt.SystemPromptBuilder;
import dev.pai.model.ChatRequest;

/**
 * Registers {@code load_skill}, injects the skill index into every system prompt through
 * {@link AgentHooks#prepareRequest}, and adds {@code /skills} plus one {@code /skill:<name> [args]} command per skill.
 */
public final class SkillsExtension implements Extension {

    /** Extension id. */
    public static final String ID = "skills";
    /** Heading of the injected system prompt section. */
    public static final String SECTION = "Skills";

    private final SkillRegistry registry;

    /**
     * Creates the extension.
     *
     * @param registry the skills; scanned on load
     */
    public SkillsExtension(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onLoad(ExtensionContext ctx) {
        registry.reload();
        ctx.registerTool(new LoadSkillTool(registry));
        String index = registry.indexText(SkillRegistry.DEFAULT_INDEX_SKILLS, SkillRegistry.DEFAULT_INDEX_CHARS);
        if (!index.isEmpty()) {
            ctx.addHooks(new AgentHooks() {
                @Override
                public ChatRequest prepareRequest(ChatRequest request, LoopState state) {
                    String prompt = new SystemPromptBuilder().base(request.systemPrompt().orElse(""))
                        .section(SECTION, index).build();
                    return new ChatRequest(request.model(), java.util.Optional.of(prompt), request.messages(),
                        request.tools(), request.options());
                }
            });
        }
        ctx.registerCommand(command("skills", "List available skills", (args, c) -> new CommandResult(list(), false)));
        for (Skill s : registry.all()) {
            ctx.registerCommand(command("skill:" + s.name(), "Use skill " + s.name(), (args, c) -> {
                c.sendUserMessage(("Use skill `" + s.name() + "`. " + args).strip());
                return new CommandResult("", false);
            }));
        }
    }

    private String list() {
        if (registry.all().isEmpty()) {
            return "no skills (looked in " + registry.roots() + ")";
        }
        return registry.all().stream().map(s -> s.name() + "  " + s.description() + "  [" + s.dir() + "]")
            .collect(Collectors.joining("\n"));
    }

    private static SlashCommand command(String name, String description, Body body) {
        return new SlashCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public CommandResult run(String args, CommandContext ctx) {
                return body.run(args, ctx);
            }
        };
    }

    /**
     * Body of a command.
     */
    @FunctionalInterface
    private interface Body {
        CommandResult run(String args, CommandContext ctx);
    }
}
