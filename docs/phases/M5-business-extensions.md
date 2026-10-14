# M5 规格书：业务扩展（按需）

> 前置：M4 通过。本阶段每个子项都是独立扩展模块，是否做、先做哪个由产品决定。此处只定义边界，细节在启动时再补充成完整规格。

## 候选

### 5A. pai-ext-wechat（微信 iLink 通道）
- 旧仓库 `wechat/` 的协议知识可读后重写：QR 登录、长轮询、消息收发、账号存储。
- 形态：**无头 runner**——扩展启动一个消息循环，每条消息开一个 `AgentLoop.run()`，`AgentEvent` 由 `WechatRenderer` 消费成文本回复。
- 审批：无交互面板 → `Approver` 用非交互实现（只读允许，写/执行按白名单，其余 deny）。复用 ext-hitl 的 Guard，不复制。
- 与 cli 的关系：`pai wechat start` 子命令由扩展通过 `registerCommand`？不——cli 子命令不是 slash 命令。需要 SPI 加 `registerCliSubcommand`？**提案进报告**，主控决定。备选：独立 main class `dev.pai.ext.wechat.Main` 复用 core，不碰 cli。

### 5B. pai-ext-web（web_search / web_fetch）
- 旧 `web/` 的 SearchProvider（SearxNG/SerpAPI/智谱）、`HtmlExtractor`（jsoup）、`NetworkPolicy` 可参考。
- 工具 `web_search(query, limit)`、`web_fetch(url, maxChars)`，effect NETWORK。
- 若 MCP 侧已有 search server（如 step_search），优先建议用户用 MCP，本扩展保持最小。

### 5C. pai-ext-plan（可选，谨慎）
- pi 的立场：plan 用文件（`PLAN.md`）+ 普通 loop 就够。先验证这一点，**不要**复活旧 `plan/runtime` 的 DAG 调度器。
- 若确有需要：一个 `plan` 工具写/读 `.pai/plan.md` + 系统提示词段落，几十行代码。

### 5D. pai-ext-subagent（可选）
- 一个 `spawn_agent(task, tools[])` 工具：在同一进程开新 `AgentLoop`（新 session 文件，`parentSession` 指回），结果以文本返回。串行即可。
- 不做团队/角色/reviewer 体系。

### 5E. RPC / JSON 模式（pai-cli）
- `pai --json "prompt"`：AgentEvent 逐行 JSON 到 stdout，供脚本/IDE 集成。
- `pai --rpc`：stdin 收 JSON 命令、stdout 发事件。这是 M1 事件化设计的直接红利，成本很低，建议早做。

## 不做清单（除非 ADR 推翻）
RAG 代码索引、LSP 诊断、side-git 快照、HTTP runtime API、租约/恢复、多 agent 编排、Lanterna TUI。

## 每个子项的交付要求
与前几阶段相同：规格 → 实施 → `mvn verify` 全绿 → ArchUnit → 验收清单 → 报告。core 零改动为默认预期，任何 SPI 变更走 ADR 修订。
