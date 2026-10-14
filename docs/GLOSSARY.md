# 术语表

代码、文档、报告里只用这些词。需要新词 → 先在此加一行（主控批准）再进代码。旧仓库同义词列出来是为了**禁止**再用。

| 术语 | 代码名 | 定义 | 禁用的旧叫法 |
|---|---|---|---|
| Session | `SessionId`、`SessionStore`、`SessionEntry` | 一个 JSONL 文件；一组有树结构的 entry；绑定一个 cwd | conversation、thread、runtime session |
| Entry | `SessionEntry` 及子类 | session 文件里的一行；有 `id/parentId/timestamp` | event（session 层面）、record、row |
| Message | `Message` 四子类 | 发给/来自模型的一条消息；是 `MessageEntry` 的载荷 | chat message、history item |
| Content block | `ContentBlock` 四子类 | 一条消息里的一段：文本/思考/图片/工具调用 | part、segment、chunk |
| Tool call | `ContentBlock.ToolCall` | 模型请求执行某工具的一个块；有 `id/name/arguments` | function call、action |
| Tool result | `ToolResultMessage`、`ToolResult` | 对一个 tool call 的执行结果；前者是消息，后者是工具返回值 | observation、tool output |
| Tool | `Tool` | 可被模型调用的能力：定义 + 效果 + 执行 | function、capability、action |
| Run | `AgentLoop.run()` | 一次用户输入触发的完整处理，直到模型不再调用工具或达到上限 | task、job、execution |
| Turn | `TurnStart/TurnEnd` | run 内的一次"模型回复 + 其工具调用与结果" | step、iteration、round |
| Stream event | `StreamEvent` | provider 流式返回的一个增量 | delta、chunk（保留给 SSE 原始数据） |
| Agent event | `AgentEvent` | loop 对外发布的一个事件 | callback、notification |
| Hook | `AgentHooks` 的方法 | 扩展介入 loop 的固定点 | interceptor、middleware、plugin point |
| Extension | `Extension` | 通过 SPI 注册工具/命令/provider/hooks 的单元 | plugin、module、addon |
| Provider | `Provider` | 一个模型 API 端点的实现（Anthropic、某 OpenAI-compat 服务） | client、gateway、backend、LLM |
| Model | `ModelRef` | 某 provider 下的一个具体模型及其能力/限制 | engine、deployment |
| Codec | `*Codec` | provider 中立类型 ↔ wire format 的双向翻译 | serializer、mapper、adapter |
| Compaction | `CompactionEntry`、`Compactor` | 把早期消息摘要成一条以腾出上下文；不改历史 | compression、summarization（泛指时可用）、context projection |
| Context | `buildContext()` 的返回 | 当前要发给模型的 message 列表 | window、prompt history |
| System prompt | `SystemPromptBuilder` | 发给模型的系统指令，每次请求重建 | base prompt、instructions |
| Skill | `Skill` | 一个带 `SKILL.md` 的目录；索引注入、正文按需加载 | — |
| Slash command | `SlashCommand` | 用户输入 `/name` 触发的本地命令，不经过模型 | cli command（保留给 `pai xxx` 子命令） |
| Approval | `ToolDecision`、`Approver` | hook 对某 tool call 的允许/阻止 | HITL（可在文档里泛指）、permission、policy decision |
| Effect | `ToolEffectEnum` | 工具的副作用类别，用于并发与审批决策 | risk level、descriptor |
| Cancel | `CancelToken` | 协作式取消信号 | abort（pi 用词，文档引用 pi 时可保留）、interrupt |
| Budget | `02-governance` §4 | 机器检查的数量上限 | limit、quota |
