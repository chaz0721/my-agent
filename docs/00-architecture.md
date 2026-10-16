# pai-next 架构总纲

> 本文是所有实施 session 的首读文件。它定义**形状**：模块、依赖方向、核心类型、纪律。
> 具体每阶段做什么见 `phases/M*.md`；为什么这样决定见 `adr/`。
> 冲突时优先级：ADR > 本文 > 阶段规格 > 实施 session 的判断。实施 session 如认为需要偏离，先停下来在交接报告里提出，不要自行偏离。

## 1. 目标与非目标

**目标**：一个 Java 实现的终端 coding agent，形状对标 [pi](https://github.com/badlogic/pi-mono)：
- 极小内核：loop + provider 抽象 + 4 个内置工具 + session + 压缩 + 扩展 SPI
- 所有"功能"（MCP、记忆、HITL 审批、微信通道、国产 provider 特殊逻辑）都是扩展
- 多 provider 原生支持：OpenAI Chat Completions 系（覆盖 GLM/DeepSeek/Kimi/Step/讯飞/Agnes 等）和 Anthropic Messages，消息类型 provider 中立

**非目标（内核永远不做）**：plan/DAG 调度器、多 agent 编排框架（子代理作为一个工具是允许的扩展）、RAG 向量库、LSP、side-git 快照、HTTP runtime API、SQLite 迁移链、租约/恢复状态机、Lanterna TUI。这些要么作为扩展、要么不做。

**与旧仓库 `paicli/` 的关系**：零件库，不是地基。允许"读旧代码后重写"，禁止"复制旧文件后改"。可搬运清单见 §7。

## 2. 模块与依赖方向

```
pai-model      消息类型 · Provider SPI · 流事件协议 · OpenAI/Anthropic 编解码 · SSE 传输
   ▲
pai-core       Tool 契约 · ToolRegistry · AgentLoop · AgentEvent · Session(JSONL) · 压缩 · Extension SPI
   ▲
pai-tools      4 个内置工具：read / write / edit / bash（作为一个 Extension 实现）
   ▲
pai-tui        JLine 渲染：订阅 AgentEvent，纯展示，无业务逻辑
   ▲
pai-cli        组装：加载扩展、REPL、slash 命令注册表。薄。
   
pai-ext-*      每个扩展一个模块（M3 起）：ext-mcp、ext-hitl、ext-memory、ext-wechat ...
               只依赖 pai-core（和 pai-model）。永不依赖 pai-cli / pai-tui。
```

**依赖规则（用 ArchUnit 写成测试，违反即构建失败）**：
1. `pai-model` 只能依赖 JDK + Jackson（+ 测试库）。不得 import `dev.pai.core..`。
2. `pai-core` 不得 import `dev.pai.tui..`、`dev.pai.cli..`、任何 `dev.pai.ext..`。
3. `pai-core` 对外只暴露 `interface`、`record`、`enum`、`sealed interface`；实现类 package-private 或放在 `..internal..` 包，`internal` 包不得被其他模块 import。
4. 任何模块不得出现名字含 `Legacy`、`Adapter`、`Compat`、`Bridge` 的类（例外：`pai-model` 内 provider 编解码可叫 `*Codec`）。需要适配说明接口错了，改接口。
5. 核心层（model/core）不得出现中文字面量字符串；用户可见文案归 tui/cli。核心层只抛类型化异常/返回类型化错误。

Maven 坐标：`dev.pai:pai-parent`，子模块 `pai-model`、`pai-core`、`pai-tools`、`pai-tui`、`pai-cli`。Java **21**（sealed + record pattern + switch 模式匹配）。

## 3. 五个地基决定（详见 ADR）

| # | 决定 | ADR |
|---|---|---|
| 1 | 消息类型 provider 中立：`Message` sealed 四种，`AssistantMessage.content` 是 `ContentBlock` 列表（Text/Thinking/ToolCall/Image）。OpenAI/Anthropic 各有 Codec 做双向翻译 | 0001 |
| 2 | 工具契约：一个 `Tool` = 定义(名/描述/JSON Schema) + 效果元数据 + `execute(JsonNode args)`，五样东西一个对象。禁止 `Map<String,String>` 参数 | 0002 |
| 3 | Loop 只产事件：`AgentLoop` 推送 `AgentEvent`，不碰终端、不知道渲染 | 0003 |
| 4 | Session 是 JSONL 追加写的树：每行一个 entry，带 `id/parentId`；不以数据库为主存 | 0004 |
| 5 | 扩展 SPI 第一天就有，内置工具也通过它注册；SPI 的事件/钩子面是有限枚举，不开放内部对象 | 0005 |

## 4. 核心类型速查（权威定义在 M1 规格书里，这里是导览）

```
dev.pai.model
  Message            sealed: SystemMessage | UserMessage | AssistantMessage | ToolResultMessage
  ContentBlock       sealed: Text | Thinking | Image | ToolCall(id, name, JsonNode arguments)
  Usage, StopReasonEnum  record / enum(STOP, LENGTH, TOOL_USE, ERROR, ABORTED)
  ToolDefinition     record(name, description, JsonNode parametersSchema)
  ModelRef           record(providerId, modelId, baseUrl, contextWindow, maxOutputTokens, capabilities)
  ChatRequest        record(model, systemPrompt, messages, tools, options)
  StreamEvent        sealed: Start | TextDelta | ThinkingDelta | ToolCallStart | ToolCallDelta | ToolCallEnd | Done | Error
  Provider           interface: id(); AssistantMessage stream(ChatRequest, Consumer<StreamEvent>, CancelToken)
  CancelToken        协作式取消；core 复用，不另定义

dev.pai.core
  tool.Tool, tool.ToolEffectEnum, tool.ToolResult, tool.ToolContext, tool.ToolRegistry
  loop.AgentLoop, loop.AgentEvent (sealed), loop.AgentHooks, loop.ToolDecision, loop.LoopState
  session.SessionEntry (sealed), session.SessionStore, session.SessionContextBuilder
  compaction.Compactor (interface), compaction.ContextBudget
  ext.Extension, ext.ExtensionContext, ext.SlashCommand
```

## 5. 阶段路线

| 阶段 | 一句话 | 完成判据 |
|---|---|---|
| M1 | 地基：model + core + 4 工具 + 最简 CLI，两个 provider 跑通 loop | 用 Anthropic 和任一 OpenAI-compat 模型，在终端里让 agent 读文件、改文件、跑命令并流式输出；session 落 JSONL 可 `/resume` |
| M2 | 可用：JLine TUI、事件渲染、压缩、`/model`、skill 索引 + `load_skill` | 日常能当工具用；200k 上下文自动压缩不崩 |
| M3 | 扩展 SPI 定型 + MCP 作为第一个扩展 | 一个外部 MCP server（stdio + HTTP）的工具出现在工具列表并可调用；核心零改动 |
| M4 | ext-hitl（PathGuard/CommandGuard/审批）、ext-memory（小）、国产 provider 全部补齐 | 危险命令被拦；`/remember` 可存可查可删；7 个旧 provider 全部能用 |
| M5 | 编排扩展（Claude Code 式）：任务清单 + 验证钩子 + 只读模式 | 多文件任务能先列清单再逐项做，edit 后自动编译回灌；`/plan` 只读探索 |
| M6 | 上下文与记忆：微压缩、结构化摘要、压缩→记忆候选、MEMORY.md 形态 | 全量压缩次数下降；摘要含 Files touched；记忆可手编 |
| M7 | harness 加固：每轮管线与终止原因、工具按输入判定（shell 词法）、审批持久化、压缩后文件恢复、性能基线 | 7 种终止原因可测；PLAN 由 effect(input) 驱动；有性能数字 |
| M7.5 | 子代理（explore/reviewer，侧链 transcript，禁止嵌套，写冲突预检）——原 M5.5 | 父 session 不含子代理读过的文件正文；子代理间同文件写串行；父取消传递（已完成，tag M7.5） |
| M8 | 评测：`--print` 非交互模式、SWE-bench Lite 30 题 × {full, bare} 同模型对照、报告含局限 | 两组 resolved 率与每题表；脚本可复现 |
| M8.5 | 日常可用性小修：扩展指引 prompt、verify Python 规则、TUI 子代理进度、敏感文件只读转审批 | 三道 live 任务改后用上扩展；cli/core 零改动 |
| M9 | TUI 升级（Claude Code 形态）：Markdown 着色、工具卡片折叠、着色 diff、底部固定输入/状态栏、选择框、主题与降级 | 三种 Windows 终端录屏；Plain 输出不变；core/cli 零改动 |
| 收口 | 面试追问清单（docs/INTERVIEW.md）、简历重写建议（docs/RESUME.md）已写；之后由用户实际使用一周收集下一批问题 | 进行中 |

每阶段结束必须：`mvn -q verify` 全绿（测试默认开）、ArchUnit 通过、本阶段规格书的"验收清单"逐条勾掉、写一份 ≤1 页的交接报告放 `docs/reports/M*-report.md`。

## 6. 工程纪律（实施 session 必须遵守）

流程、CI、API 快照、预算与接口准入见 `02-governance-and-harness.md`；术语见 `GLOSSARY.md`；既有裁决见 `DECISIONS-LOG.md`。代码风格见 `01-code-style.md`：基准是《阿里巴巴 Java 开发手册》黄山版。构建强制层（Spotless + p3c 格式模板、P3C-PMD、Error Prone + NullAway、ArchUnit、JaCoCo、Enforcer）M1 建骨架时必须先接入；审查约定与对手册的偏离表见该文件 §3–§4。

1. **测试默认开**。`pom.xml` 不允许 `skipTests=true`。慢测试用 JUnit 5 `@Tag("slow")`，CI 全跑，本地 `-DexcludedGroups=slow`。
2. **provider 用录制回放测试**：真实 SSE 流存为 `src/test/resources/fixtures/<provider>/*.sse`，Codec 测试不需要 API key。有 key 时另跑 `@Tag("live")`。
3. **不写行为规则文档**。能写成类型就写类型，能写成测试就写测试，剩下的写 ADR。不要再出现 32KB 的 AGENTS.md。
4. **规格书里的签名是合同**。实施时可以加 private/包私有的东西，不能改公开签名；觉得签名不对，停下来在报告里提。
5. **一个 PR/一个阶段只做规格书范围内的事**。看到"顺手可以加"的东西写进报告的"建议"节，不要做。
6. **不要往新仓库复制旧文件**。看旧代码、理解、重写。commit 里不要出现 `paicli/` 路径。
7. 公开 API 全部 Javadoc 一句话说清职责；没有职责说不清楚的类。

## 7. 旧仓库可搬运清单（读后重写，不复制）

| 旧位置（`paicli/src/main/java/com/paicli/`） | 搬去 | 搬什么 |
|---|---|---|
| `llm/AbstractOpenAiCompatibleClient.java` :111-220 SSE 解析, :415-460 tool_calls delta 按 index 合并, :251-301 `reasoning_content`/多种 cache 字段名 | `pai-model` OpenAiCompletionsCodec | **知识**：各家 SSE 字段差异、DeepSeek/Kimi 要回传 `reasoning_content`、DeepSeek 强制 HTTP/1.1 |
| `llm/*Client.java` 7 个 | `pai-model` 的 provider 预设表（M4） | base URL、鉴权 header、模型名、特殊 header（讯飞 `lora_id`） |
| `mcp/McpClient` + `transport/Stdio*`、`StreamableHttp*` + `notifications/NotificationRouter` + `jsonrpc/*` | `pai-ext-mcp`（M3） | JSON-RPC 客户端、两种 transport、`list_changed` 刷新、cursor 分页、生命周期版本号 |
| `skill/SkillRegistry`、`SkillFrontmatterParser`、`SkillIndexFormatter` | `pai-core` skill（M2） | 三级目录扫描、frontmatter、索引 + `load_skill` 按需 |
| `prompt/PromptAssembler` + `resources/prompts/*.md` | `pai-core` prompt（M1 精简版） | 分段拼装思路；**正文重写到 <1000 token** |
| `policy/PathGuard`、`CommandGuard`、`tool/runtime/FileCasWriter`、`ReadStamp` | `pai-ext-hitl`（M4） | 路径逃逸检测、命令黑名单、读后写 CAS |
| `context/compaction/ContextCompactionService` | `pai-core` compaction（M2） | **只搬思路**：阈值 = window − 预留；摘要 + 保留尾部；不搬 SQLite 投影 |

**明确不搬**：`cli/Main.java`、`tool/ToolRegistry.java`、`agent/*` 四个入口、所有 `Legacy*`/`*Adapter`、`model/ModelGateway` 及其 10 个协作类、`memory/` 30 类、`rag/`、`lsp/`、`snapshot/`、`runtime/recovery|task|api|data`、`persistence/`、`tui/`（Lanterna）、`render/`、`wechat/`（M5 再看）。

## 8. 参考实现：pi

实施 session 应本地 clone 一份只读参考：`git clone --depth 1 https://github.com/badlogic/pi-mono.git /tmp/pi-ref`。对照文件：
- `packages/ai/src/types.ts` — 消息/内容块/事件协议/Tool/Model（→ pai-model）
- `packages/agent/src/types.ts`、`agent-loop.ts` — loop 配置、hooks、AgentEvent、工具并行执行（→ pai-core loop）
- `packages/coding-agent/src/core/session-manager.ts` — JSONL entry 类型、树、投影（→ pai-core session）
- `packages/coding-agent/src/core/extensions/types.ts` — 扩展事件面（→ pai-core ext；只取子集）
- `packages/mcp/` — 独立 MCP client（→ pai-ext-mcp）

TS→Java 的类型映射约定见 `reference/pi-type-mapping.md`。**不要翻译 pi 代码**；读懂协议后用 Java 习惯写。
