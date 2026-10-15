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
| Project context | `prompt.ProjectContext`、`PAI.md` | 项目级规则文件，启动时注入 system prompt 的 `<project-context>` 段；与 Memory（个人事实）区分 | project memory、CLAUDE.md（引用 Claude Code 时可保留） |
| Skill | `Skill` | 一个带 `SKILL.md` 的目录；索引注入、正文按需加载 | — |
| Slash command | `SlashCommand` | 用户输入 `/name` 触发的本地命令，不经过模型 | cli command（保留给 `pai xxx` 子命令） |
| Approval | `ToolDecision`、`Approver` | hook 对某 tool call 的允许/阻止 | HITL（可在文档里泛指）、permission、policy decision |
| Project trust | `ProjectTrust`、`~/.pai/trust.json`、`ExtensionContext.projectTrusted()` | 用户对某 cwd 的一次性确认；未信任时不加载该目录下会执行代码或改写凭据去向的配置 | trusted workspace、allowlist |
| Approval mode | `ApprovalModeEnum`（ASK / AUTO_READ / YOLO / PLAN） | ext-hitl 决定哪些 tool call 需要用户确认 | permission mode、safety level |
| Guard | `PathGuard`、`CommandGuard` | 审批之前的确定性策略，命中即拒绝且不可批准 | policy、firewall、blacklist（规则文件内容可用） |
| Provider preset | `ProviderPreset`、`providers.json` | 某 provider 的 URL/鉴权变量/flags/模型表 | profile、vendor config |
| Task list | `todo_write`/`todo_read`、`CustomEntry{tasks}`、`pai-ext-tasks` | 模型为多步工作维护的清单（pending/in_progress/completed）；是模型的工作记忆，不是项目管理 | todo（可作命令名）、plan（保留给 Plan mode）、checklist |
| Plan mode | `ApprovalModeEnum.PLAN`、`/plan`、`.pai/plan.md` | 只读探索模式：写/执行/网络被拦，模型先出方案 | read-only mode、dry run |
| Verification rule | `pai-ext-verify`、`.pai/verify.json` | 写入后自动运行的构建/测试命令及其回灌 | post-write hook、CI（保留给 GitHub Actions） |
| Effect | `ToolEffectEnum` | 工具的副作用类别，用于并发与审批决策 | risk level、descriptor |
| Cancel | `CancelToken` | 协作式取消信号 | abort（pi 用词，文档引用 pi 时可保留）、interrupt |
| Budget | `02-governance` §4 | 机器检查的数量上限 | limit、quota |

## M7 新增
| 术语 | 含义 | 代码 |
|---|---|---|
| Termination reason | 一次 run 结束的原因：COMPLETED / MAX_TURNS / ABORTED / PROMPT_TOO_LONG / MAX_OUTPUT_TOKENS / PROVIDER_ERROR / HOOK_ERROR | `TerminationReasonEnum`、`AgentEvent.AgentEnd.reason` |
| Context stage | loop 每轮在钩子链之后、provider 之前对上下文做的固定阶段（微压缩、全量摘要）；封闭 SPI，扩展不能注册 | `ContextStage`、`Compaction.stages()` |
| Per-input effect | 工具按本次输入细化的效果等级，只能比声明值更安全；bash 据只读表与词法判定 | `Tool.effect(JsonNode)`、`Tool.effectOf`、`ShellCommands`、`ReadOnlyRules` |
| Read-only table | bash 只读命令及其禁用选项的资源表（工具自描述，不是审批策略） | `pai-tools` `read-only-commands.txt` |
| Permission rule | 持久化的审批规则 allow/deny（用户级 `~/.pai/ext/hitl/permissions.json`，项目级仅受信任时合并且不能放宽用户级 deny）；deny 不可批准 | `Permissions`、`/approvals` |
| Restored files | 全量摘要后把 `## Files touched` 中的文件当前内容回灌为一条 user 消息，使模型不必重读即可 edit | `FileRestorer`、`<restored-files>` |
| Circuit breaker（压缩熔断） | 连续 3 次摘要失败（含"成功仍超阈值"）后停止自动压缩，手动 `/compact` 成功清零 | `Compaction.MAX_FAILURES` |
