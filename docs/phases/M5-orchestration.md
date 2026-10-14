# M5 规格书：编排与工作流扩展（Claude Code 式）

> 前置：M4 通过。设计来源：Claude Code 公开文档中的机制（TodoWrite、subagents、hooks、plan mode、CLAUDE.md、compaction）与 pi 的扩展模型。**不是**复活旧仓库的 DAG/Planner/Reviewer——编排从框架搬进模型 + 四个薄机制：任务清单、子代理、验证钩子、只读模式。
> 全部是扩展；内核零改动是默认预期。M4 的 `ApprovalModeEnum` 需加一个值（5D），属 ext-hitl 内部，不是 SPI 变更。
> **分量控制（个人开发，别一次做全）**：
> - **M5 完成定义 = 5A + 5C + 5D**。三者都是"一个工具/一个钩子 + 几十行配置解析"，合计预计 ≤ 600 行主代码，一个 session 可完成。
> - **5B 子代理 = M5.5**，用了一段时间后确实需要上下文隔离再做；预计 400–600 行。
> - **5E / 5F / 5G 是备选**，有真实需求再开；5F 若 M2 已顺手做了就不重复。
> - 任何子项做完都要能独立关掉（扩展不加载即无影响），所以"先做三个"不会留下半成品。

## 设计原则（对照旧 DAG）

| 旧 Plan Runtime 的职责 | 新归属 |
|---|---|
| Planner 拆任务、严格 schema、两次有界修复 | 模型自己拆，写进任务清单工具（5A）；重规划 = 模型改清单 |
| Worker 并发执行同层节点 | 子代理（5B），同时获得上下文隔离 |
| 确定性 verification + Reviewer | 验证钩子（5C）：写文件后自动跑编译/测试；需要"审查"时主 agent 开一个子代理当 reviewer |
| 资源冲突预检、锁 | 子代理并发时按 `ToolEffectEnum` + 路径做冲突预检（5B） |
| Plan 审阅交互（Enter 执行/ESC 取消） | 只读模式（5D）：模型先在 PLAN 模式下探索并写方案，用户切回正常模式再执行 |
| UNKNOWN / at-most-once 恢复 | 放弃。工具结果即事实，崩溃靠 session 树 `/fork` 回退 |

## 5A. pai-ext-tasks（任务清单）

Claude Code 的 TodoWrite：模型用它把多步任务写成可见清单并逐项更新，用户在 TUI 看进度。

```java
public final class TasksExtension implements Extension { id()="tasks"; }
// 工具 todo_write(items: [{id, content, status: pending|in_progress|completed, activeForm?}])  WRITE_FS? 否—— effect=READ_ONLY（只改内存+session），concurrencySafe=false
// 工具 todo_read()  READ_ONLY
```
- 状态存在 session 的 `CustomEntry{customType:"tasks"}`，每次 `todo_write` 追加一条（历史可追溯），`buildContext` 忽略。
- `prepareRequest` 钩子：若清单非空，把当前清单以 `<tasks>` 段注入 system prompt（≤ 1500 字），并加一句固定指引："多步任务先写清单；同一时间只有一项 in_progress；完成立即更新"。
- TUI：`AgentEvent` 监听 `ToolExecutionEnd(todo_write)` → 刷新底部/侧栏清单（`pai-tui` 加一个可选 widget 接口 `Renderer.sidebar(List<String>)`，默认空实现——这是 tui 的变更，不是 core）。
- `/todo` 命令打印清单；`/todo clear`。
- **不做**：依赖关系、优先级、owner、持久化到 session 之外。清单是模型的工作记忆，不是项目管理工具。

## 5B. pai-ext-subagent（子代理）

Claude Code 的 Agent 工具：主 agent 把一个独立子任务交给新上下文执行，只拿回结论。核心价值是**上下文隔离**，其次才是并发。

```java
public final class SubagentExtension implements Extension { id()="subagent"; }
// 工具 spawn_agent(task: string, agentType?: string, tools?: string[], model?: string, background?: boolean)  effect=EXECUTE, concurrencySafe=true
//      返回：子代理最终 assistant 文本 + 子 session id + usage 摘要
// 工具 agent_result(id)  READ_ONLY —— background=true 时轮询取结果
public record AgentType(String name, String description, String systemPromptAddendum, Set<String> allowedTools, Optional<String> model) {}
```
- 预置 `AgentType`：`general`（全部工具）、`explore`（只读工具：read/grep/glob/bash 的只读白名单，用于"在仓库里找 X"）、`reviewer`（只读 + 固定审查提示词，输出结构化问题列表）。用户可在 `~/.pai/agents/*.md`（frontmatter: name/description/tools/model + 正文为提示词）加自定义类型，格式对齐 Claude Code 的 `.claude/agents/`。
- 实现：同进程新建 `AgentLoop`，新 session 文件（header `parentSession` 指回父 session，`SessionHeader` 已预留），工具集 = 父 registry 按 `allowedTools` 过滤，hooks 链复用父 hooks（审批照常生效）。子代理**不能**再 `spawn_agent`（深度 1），避免递归失控。
- 并发：`background=true` 时在虚拟线程运行，主 loop 继续；多个子代理间做**资源冲突预检**——两个子代理的 `WRITE_FS` 工具调用解析到同一路径时后者等待（简单的 per-path `ReentrantLock`，由本扩展的 `beforeToolCall` 钩子实现）。这是旧 DAG 里唯一值得留的调度逻辑，几十行。
- 事件：子代理的 `AgentEvent` 以 `ToolExecutionUpdate(partial)` 形式向父 loop 汇总（每个 turn 一行摘要），TUI 折叠显示；`PAI_DEBUG_SUBAGENT=1` 时全量透传。
- 预算：子代理 `maxTurns` 默认 30；父传 `CancelToken`，父取消则子全部取消。
- **不做**：子代理间通信、团队/角色协商、Reviewer 强制门禁。需要审查就让模型自己决定 spawn `reviewer`。

## 5C. pai-ext-verify（确定性验证钩子）

旧 DAG "FILE_WRITE 节点必须有 verification" 的核心价值，拿掉 schema 只留机制。

```java
public final class VerifyExtension implements Extension { id()="verify"; }
// 配置 <cwd>/.pai/verify.json：[{"on": "write|edit", "pathGlob": "**/*.java", "run": "mvn -q compile", "timeoutSeconds": 120, "mode": "append|replace"}]
```
- `afterToolCall`：匹配到规则 → 跑命令 → 非 0 退出时把输出尾部（≤ 2000 字）**追加**进该工具的 `ToolResult`（`mode=append`），标 `isError=false`（不是工具失败，是验证反馈），模型下一轮自然看到并修。
- 同一轮多个写入只跑一次（debounce 到 turn 末）：用 `AgentHooks.transformContext` 时机做批量——若实现困难，退化为每次写入都跑，M5 报告里说明。
- `/verify` 命令手动触发；`/verify off` 本会话关闭。
- 默认规则（无配置文件时）：检测到 `pom.xml` → `mvn -q compile`；`package.json` → `npm run -s typecheck || npm run -s build --if-present`；`Cargo.toml` → `cargo check`；`go.mod` → `go build ./...`。
- **不做**：LLM Reviewer、验收 schema、失败计数与重规划。失败就是回灌，模型自己决定下一步。

## 5D. 只读/计划模式（放进 ext-hitl）

Claude Code 的 plan mode：模型只能读、不能改，先给方案；用户看完切回正常模式执行。替代旧版的"Plan 审阅交互"。

- `ApprovalModeEnum` 加 `PLAN`：`beforeToolCall` 对 `WRITE_FS`/`EXECUTE`/`NETWORK`（除白名单只读命令）一律 `block("plan mode: read-only")`，并在 `prepareRequest` 注入一段："当前为只读规划模式；探索代码后输出分步方案，不要尝试修改"。
- `/plan` 进入；`/plan off` 或用户在方案后回答"执行"时由 TUI 提供快捷切换（`Shift+Tab` 循环 ASK → AUTO_READ → PLAN，与 Claude Code 一致）。
- 方案文本可用 `/plan save` 落到 `.pai/plan.md`，下一轮正常模式自动以 `<plan>` 段注入一次。
- `bash` 在 PLAN 模式的只读白名单：`ls|cat|head|tail|grep|rg|find|git (status|log|diff|show)|wc|tree`——其余 block。

## 5E. pai-ext-shell-hooks（用户自定义 shell 钩子）

Claude Code 的 hooks：用户用 settings 里的 shell 命令在 `PreToolUse/PostToolUse/Stop/...` 介入，不写代码。对个人开发者是最高杠杆的扩展点（格式化、通知、审计、禁某些命令）。

```java
public final class ShellHooksExtension implements Extension { id()="shell-hooks"; }
// 配置 ~/.pai/hooks.json 与 <cwd>/.pai/hooks.json 合并：
// {"PreToolUse":[{"matcher":"write|edit","command":"bash .pai/hooks/fmt.sh","timeoutSeconds":10}],
//  "PostToolUse":[...], "Stop":[...], "SessionStart":[...]}
```
- 事件 → hook 映射：`PreToolUse` ↔ `beforeToolCall`、`PostToolUse` ↔ `afterToolCall`、`Stop` ↔ `AgentEnd`、`SessionStart` ↔ `SessionStarted`。
- 协议对齐 Claude Code：stdin 给 JSON（tool_name、tool_input、session_id、cwd），退出码 0 放行、2 阻止（stderr 作为 reason 回灌模型）、其他记日志放行；stdout 若是 JSON 且含 `updatedInput` 则替换参数（仅 Pre）。
- `matcher` 是对工具名的正则。
- 超时 kill，默认 60s。

## 5F. 项目记忆文件（放进 M2 prompt，此处补规格）

Claude Code 的 `CLAUDE.md`。旧仓库的 `PAI.md` 思路正确，保留：
- 加载顺序：`~/.pai/PAI.md` → `<cwd>/PAI.md` → `<cwd>/.pai/PAI.md` → `PAI.local.md`（gitignore）；支持 `@relative/path.md` 导入；总预算 8000 字，超了截断并提示。
- 以 `<project-context>` 段注入 system prompt。
- `/init`：让模型读仓库后生成一份 ≤ 60 行的 `PAI.md`（命令、结构、坑、禁忌）；已存在不覆盖。
- `/memory`（与 ext-memory 的记忆区分：这是项目规则，那是个人事实）→ 打开文件路径提示。
- 这条实际在 **M2** 做（属于 prompt 组装），此处只是把规格补齐；M2 规格开头加一行引用。

## 5G. 其他候选（不展开，需要时另写规格）

- `pai-ext-web`：`web_search`/`web_fetch`，effect NETWORK；旧 `web/` 的 SearchProvider/HtmlExtractor 可参考。若已有 MCP search server 优先用 MCP。
- `pai-ext-wechat`：无头 runner，每条消息一个 `AgentLoop.run()`；审批用非交互 `Approver`（只读允许、写/执行白名单）；复用 ext-hitl 的 Guard。入口用独立 main class，不改 cli。
- RPC/JSON 模式（`pai --json`、`pai --rpc`）：事件流直出，供脚本/IDE 用；成本极低，建议随 5A 一起做。
- 后台 bash（`run_in_background`）：Claude Code 有，pi 推荐 tmux。先不做，观察需求。

## 测试清单（按子项）

- 5A：`todo_write` 状态校验（只能一个 in_progress）、CustomEntry 追加、prompt 注入截断。
- 5B：`explore` 类型拿不到 write 工具；深度 1 限制；两个后台子代理写同一文件时串行；父取消传递；`~/.pai/agents/*.md` 解析。
- 5C：规则匹配、非 0 回灌、超时、debounce、默认规则探测。
- 5D：PLAN 模式下 write 被 block、只读 bash 白名单、切换后放行、plan.md 注入一次。
- 5E：退出码 0/2/其他三种语义、stdin JSON 字段、`updatedInput` 替换、超时 kill、matcher 正则。
- 5F：加载顺序与覆盖、`@import`、预算截断、`/init` 不覆盖。

## 验收清单

- [ ] 真实模型：给一个需要改 3 个文件 + 跑测试的任务，观察到：先写 todo → 逐项执行 → 每次 edit 后 verify 自动跑编译 → 失败回灌后模型自修 → 清单全部 completed。
- [ ] `spawn_agent(explore)` 在一个陌生仓库里找到某个函数的所有调用点，主 agent 上下文没有被子代理读过的文件内容污染（看 session jsonl）。
- [ ] 两个 background 子代理同时改同一文件时，第二个等待而不是覆盖。
- [ ] `/plan` 模式下模型尝试 write 被拦并给出方案；`Shift+Tab` 切回后执行。
- [ ] 一个 PreToolUse shell hook 以退出码 2 阻止了 `rm -rf`，模型收到 reason。
- [ ] `PAI.md` 的规则出现在请求体 system prompt 里。
- [ ] core 零改动；tui 仅新增 `sidebar` 可选接口。
- [ ] 报告 `docs/reports/M5-report.md`。
