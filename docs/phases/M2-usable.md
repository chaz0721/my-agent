# M2 规格书：可用——TUI、压缩、模型切换、Skill

> 前置：M1 验收全部通过，`docs/reports/M1-report.md` 已写。实施前读 M1 报告里的"给 M2 的建议"。
> 本阶段**不改** `pai-model` 公开签名；允许在 `pai-core` 新增包 `compaction`、`skill`，允许给 `AgentHooks` 加一个钩子 `prepareRequest`（见下），这需要同步在 ADR 0003 追加一行"修订记录"。

## 目标

日常能当工具用：JLine 交互、流式渲染不闪、长会话自动压缩不崩、`/model` 切换、skill 索引注入 + `load_skill` 按需加载、`/tree` 查看分支并从任一节点继续。

## 范围

**做**：`pai-tui` 模块；`pai-core.compaction`；`pai-core.skill`；项目记忆文件 `PAI.md` 注入（规格见 M5 §5F，很小，放这里做）；cli 命令：`/model`、`/compact`、`/tree`、`/fork`、`/skills`、`/help`、`/clear`（开新 session，不删历史）。
**不做**：MCP、审批、记忆、重试、国产 provider 预设、主题系统、鼠标。

## pai-tui 合同

```java
package dev.pai.tui;
public interface Renderer extends Consumer<AgentEvent>, AutoCloseable {
  String readLine(String prompt) throws IOException;      // 阻塞；Ctrl+C 抛 UserInterruptException；Ctrl+D 抛 EndOfFileException
  void println(String text);                              // 在输入行上方安全输出（JLine printAbove 语义）
  void status(StatusLine s);                              // 底部一行：model · ctx% · tokens · session
}
public record StatusLine(String model, int ctxPercent, Usage usage, String sessionShort, String phase /* idle|thinking|tool */) {}
public final class JLineRendererImpl implements Renderer {}
public final class PlainRendererImpl implements Renderer {}   // 非 TTY / 管道 / 测试用，M1 的 stdout 逻辑搬来
public final class ToolCallSummaries { static String summarize(ToolCall call, ToolResult r); } // 工具专属展示在这里，用 Map<String, Function> 注册，不在 core
```

渲染要求：
- `TextDelta` 流式追加，Markdown 不做复杂渲染，只处理代码块围栏着色（可选）。
- `ThinkingDelta` 在一个固定高度（≤3 行）的灰色区域滚动，正文开始前清掉；完整 thinking 不落入 transcript。
- 工具调用：一行 `▸ read src/Main.java` → 完成后同一行变 `✓ read src/Main.java (120 lines, 0.03s)`。
- 底部状态行用 JLine `Status`，**不要**手写光标移动/清屏。
- 终端宽度变化不崩。
- `RendererFactory.create()`：`System.console()!=null` 且 `TERM` 非 dumb → JLine，否则 Plain。

## compaction 合同

```java
package dev.pai.core.compaction;
public interface TokenEstimator { int estimate(List<Message> msgs, List<ToolDefinition> tools, String systemPrompt); }   // 默认 chars/4 的保守估计；可被 provider 返回的 usage.input 校准
public record CompactionPolicy(int reserveForOutput /*默认 20_000*/, int safetyMargin /*13_000*/, int keepRecentMessages /*最近 N 条不压，默认 6*/) {
  int thresholdFor(ModelRef m);       // max(window - reserve - margin, window * 0.5)
}
public interface Summarizer { String summarize(List<Message> toSummarize, LoopState state, CancelToken c); }   // 默认用当前 provider 调一次模型，prompt 固定在 resources
public final class CompactingHooks implements AgentHooks {
  public CompactingHooks(TokenEstimator est, CompactionPolicy policy, Summarizer sum, SessionStore store, Supplier<SessionId> session);
  // transformContext：超阈值 → 把 [0, len-keepRecent) 摘要成一条 UserMessage("<summary>…</summary>") 放最前 → 写 CompactionEntry → 发 AgentEvent? （不能，hooks 不发事件）→ 返回新 messages
}
```

因为 hooks 不能发事件，`AgentLoop` 需在 `transformContext` 前后比较消息列表（size 或身份），变了则发出 M1 已定义的 `AgentEvent.ContextCompacted(before, after)`——**这是本阶段对 core loop 的唯一改动**（M1 只定义了该 record，未发出）。

压缩**不能**切断一对 ToolCall/ToolResult：边界只能落在 UserMessage 或无 toolCall 的 AssistantMessage 之前。摘要失败（模型错误）→ 不压缩、记 warn、照常继续；若请求因 prompt too long 失败（provider 返回 `Error` 且 message 匹配 `context|too long|maximum` 之类）→ loop 允许**一次**强制压缩重试（`AgentConfig` 加 `boolean retryOnceOnContextOverflow`，默认 true）。

## skill 合同

```java
package dev.pai.core.skill;
public record Skill(String name, String description, Path dir, Path skillMd, Map<String,String> frontmatter) {}
public final class SkillRegistry {
  public SkillRegistry(List<Path> roots);                    // 顺序：内置 resources → ~/.pai/skills → <cwd>/.pai/skills；后者同名覆盖
  public void reload(); public List<Skill> all(); public Optional<Skill> get(String name);
  public String indexText(int maxSkills /*20*/, int maxChars /*4000*/);   // 注入 system prompt 的索引段
}
```
- `load_skill` 工具（READ_ONLY）：返回 SKILL.md 正文（≤ 32KB，超了截断并标注），由 `SkillsExtension` 注册；它也负责在 `onLoad` 时把索引段加入 `SystemPromptBuilder.section("skills", …)`。
- `/skill:name [args]` 用户直接触发：等价于插入 UserMessage("Use skill `name`. <args>")+让模型自己 load。
- frontmatter 解析支持 `name/description/allowed-tools(忽略，M4)`，格式与 Agent Skills 标准一致。参考旧 `SkillFrontmatterParser` 的边界处理（BOM、CRLF、缺 `---`）。

## `AgentHooks.prepareRequest`（新增）

```java
default ChatRequest prepareRequest(ChatRequest req, LoopState state) { return req; }
```
用途：skill 索引注入 system prompt、M4 的审批提示注入、未来的 provider 特殊参数。在每次 provider 调用前调用。ADR 0003 追加修订记录。

## 模型切换 `/model`

- `/model` 列出已配置模型（来自 `~/.pai/models.json`：数组 `{providerId, modelId, baseUrl?, apiKeyEnv, contextWindow, maxOutputTokens, capabilities[]}`）；`/model <providerId>/<modelId>` 切换，写 `ModelChangeEntry`，下一轮生效。
- 切换后若新模型不支持 IMAGE_INPUT 而历史里有 Image 块 → Codec 降级为文本占位（ADR 0001 已规定由 Codec 决定）。

## `/tree`、`/fork`

- `/tree`：按 parentId 打印树（缩进 ASCII），每节点显示 id 前 6 位、角色、首 60 字；标注当前 leaf。
- `/fork <entryId>`：把 leaf 指到该 entry，后续追加以它为 parent。不新建文件。
- `/label <entryId> <text>`：写 LabelEntry，`/tree` 显示。

## 测试清单

- `CompactionPolicyTest`：阈值公式；边界不切断 tool pair；`keepRecent`。
- `CompactingHooksTest`：FakeSummarizerImpl；超阈值触发、写 CompactionEntry、再 `buildContext` 等价。
- `AgentLoopTest` 新增：context overflow 一次重试成功 / 失败。
- `SkillRegistryTest`：三级覆盖；frontmatter 边界；索引截断。
- `JLineRendererImpl` 用 JLine 的 `DumbTerminal`/mock 做冒烟（可 `@Tag("slow")`）；`PlainRendererImpl` 全测。
- `SessionTreeTest`：fork 后 `buildContext` 走对分支。

## 验收清单

- [ ] 真实模型：连续工作到触发自动压缩（可临时把 window 调小到 8k 验证），压缩后继续对话正常，`/tree` 能看到 compaction 节点。
- [ ] `/model` 在 Anthropic 与 OpenAI-compat 间来回切换，历史含 tool_use 时两边请求体都合法（`PAI_DEBUG_HTTP=1`）。
- [ ] 放一个 `.pai/skills/demo/SKILL.md`，系统提示词出现索引，模型按需 `load_skill`。
- [ ] `/fork` 到早期节点后继续，新分支与旧分支互不影响。
- [ ] 终端缩放、Ctrl+C 中途取消、管道模式 `echo hi | pai` 全部不崩。
- [ ] `pai-core` 主代码 ≤ 4000 行。
- [ ] 报告 `docs/reports/M2-report.md`。
