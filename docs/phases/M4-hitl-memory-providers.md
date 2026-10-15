# M4 规格书：ext-hitl、ext-memory、provider 预设表与重试

> 前置：M3 通过。三个子项彼此独立，可以拆成三个实施 session 并行；但 provider 预设表应先做（另两个的真实验收需要国产模型）。

## 来自 M3 的输入（已裁决）
- SPI 变更（ADR 0005 修订记录）：`ExtensionContext.paiHome()`（返回 `~/.pai` 等价目录，布局写成合同：`sessions/`、`extensions/`、`ext/<id>/`、`skills/`、`models.json`、`mcp.json`、`.env`）；`ExtensionContext.notify(String)`（给用户的一行提示，tui 渲染，Plain 打到 stderr）。
- core bug fix：`ExtensionHost.Context` 注册表线程安全（含 `unregisterTool` 同步更新 `toolNames`）；`AgentLoop` 在 `prepareRequest` 之后从 registry 取工具定义并与钩子补入的去重合并，MCP 扩展随后删掉 `FirstRequestWait` 里的补工具逻辑。hook 调用顺序（classpath 扩展先于 jar 扩展，注册顺序链式）写进 ADR 0005 修订记录。
- 项目信任（4B）覆盖范围扩大：`<cwd>/.env`、`<cwd>/.pai/mcp.json`、`<cwd>/.pai/extensions/*.jar`、`PAI.md` 的 `@import` 绝对路径——未信任一律不加载。
- MCP 工具 `effect=UNKNOWN` → ASK 模式默认审批；`get-env` 类工具会泄露进程环境变量，审批提示里显示完整参数。
- `check-budgets.sh` 给 `pai-ext-*` 定预算 2500/模块（脚本口径，含注释）。

## 4A. provider 预设 + 重试（pai-model / pai-cli）

### 预设表
`pai-model/src/main/resources/dev/pai/model/providers.json`：
```json
[{"id":"deepseek","api":"openai-completions","baseUrl":"https://api.deepseek.com/v1","apiKeyEnv":"DEEPSEEK_API_KEY",
  "flags":{"forceHttp1":true,"echoReasoning":true,"imageInput":false},
  "models":[{"id":"deepseek-chat","contextWindow":128000,"maxOutputTokens":8192}]}, ...]
```
**来自 M1 的输入**：签名/`reasoning_content` 回放目前按 providerId 判断，所有 openai-compat 共用一个 id——预设表落地后改为 provider + model 判断；`echoReasoning` 目前对 openai-compat 一律 true，改为按预设 flag。

覆盖旧仓库 7 个 provider：GLM(zhipu)、DeepSeek、StepFun、Kimi(moonshot)、FreeLLMAPI、讯飞 xfyun（额外 header `lora_id`，不发 tools）、Agnes。知识来源：旧 `llm/*Client.java` 和 `AGENTS.md` :87-92。**只搬知识，不搬代码**。

```java
public final class ProviderPresets { static List<ProviderPreset> builtin(); static List<ProviderPreset> merge(builtin, Path userFile /* ~/.pai/providers.json */); }
public record ProviderFlags(boolean forceHttp1, boolean echoReasoning, boolean imageInput, boolean sendTools, Map<String,String> extraHeaders) {}
```
`OpenAiCompatProviderImpl` 构造改为接收 `ProviderFlags`（M1 时是 `flags(forceHttp1, echoReasoning)`，此处是**兼容扩展**：加字段不改语义）。`/model` 列表读取 `ModelRef`s 来自预设 + 用户文件。

### 重试
放在 **pai-cli 层**的 `RetryingProviderImpl implements Provider`（装饰器，不叫 Adapter——它是 Provider 的一个实现）：
- 只对 `Error` 且 **尚未产生任何 TextDelta/ToolCallStart** 的失败重试（HTTP 429/5xx/连接错误），最多 3 次，指数退避 + jitter，尊重 `Retry-After`。
- 已有 partial 输出、4xx 非 429、ABORTED → 不重试。
- 不做断路器、不做 fallback 链。需要再提 ADR。

## 4B. pai-ext-hitl（审批与策略）

**来自 M1 的输入**：
- 项目信任：首次在某个 cwd 启动且该目录存在 `.env`/`.pai/` 时，询问"信任此项目并加载其 .env 与配置？(y/n)"，答案记在 `~/.pai/trust.json`；未信任则忽略 `<cwd>/.env`（M1 复审 N1，当前只打印提示）。
- 钩子线程语义（DECISIONS-LOG 2026-10-15）：串行模式 before 钩子对全部调用预检后才执行，所以第二个调用的审批看不到第一个的结果；并行模式 after 在 worker 线程。审批 UI 必须串行化（一个锁）。

```java
public final class HitlExtension implements Extension { id()="hitl"; }
public enum ApprovalModeEnum { ASK /*默认*/, AUTO_READ /*只读免审*/, YOLO /*全免，启动参数显式开*/ }
final class PathGuard   { /* 解析 symlink 后必须在 cwd 内；allowList 目录可配 */ }
final class CommandGuard{ /* 黑名单：rm -rf /、mkfs、dd of=、curl|sh、> /dev/sd*… 正则表；只是辅助，不是主防线 */ }
final class ApprovalHooks implements AgentHooks {
  beforeToolCall: effect==READ_ONLY && mode>=AUTO_READ → allow
                  PathGuard/CommandGuard 命中 → block("policy: …")（用户不能批准）
                  否则 → 调 Approver.ask(call) → allow/block("user denied")
}
public interface Approver { Decision ask(ToolCall call, Tool tool, String rendered); }   // tui 实现：渲染 diff/命令，y/n/a(本会话总是)；非交互实现：默认 deny
```
- `edit`/`write` 审批时显示 diff（用 `details` 里的绝对路径读旧内容算 unified diff，≤ 60 行）。
- "a = 本会话此工具总允许" 的记忆放 ext 内存，不持久化。
- `Approver` 由 cli 注入（JLine 实现）；ext 不依赖 tui——通过 `ExtensionContext` 新增 `Optional<UserInteraction> ui()`？**这是一个 SPI 变更**：先在报告里提案（`UserInteraction { String ask(String prompt, List<String> choices); }`），主控批准并修 ADR 0005 后再做。暂时可用 System.console 读。

## 4C. pai-ext-memory（小）

目标：显式记忆，可审计可删除。**不做**自动抽取、不做 embedding、不做 RRF/MMR。

```java
public final class MemoryExtension implements Extension { id()="memory"; }
// 存储：~/.pai/memory/<scope>.jsonl，scope = global | <cwd-hash>；每行 {id, text, tags[], createdAt, deletedAt?}
// 工具：remember(text, tags[]) WRITE_FS 需审批；recall(query, limit) READ_ONLY → 简单 BM25/子串打分（jieba 可选，走 flag）
// 命令：/memory list|search <q>|delete <id>|clear
// 注入：prepareRequest 时把 recall(当前 user 文本, 5) 结果以 <memory> 段加到 system prompt，≤ 1500 字；可用 /memory inject off 关掉
```
secret 拒绝：写入前正则检测 API key / Bearer / 卡号形态 → block 并提示。

## 测试清单

- 预设：每个 preset 的 `toRequestBody` golden 测试（讯飞不含 tools；DeepSeek 含 reasoning_content 回传）。
- `RetryingProviderTest`：429 重试成功；partial 后错误不重试；Retry-After 尊重。
- `PathGuardTest`（symlink 逃逸）、`CommandGuardTest`（表驱动）、`ApprovalHooksTest`（三种 mode × allow/deny）。
- `MemoryStoreTest`、`RecallTest`、secret 拒绝。

## 验收清单

- [ ] 7 个国产/第三方 provider 中至少有 key 的那几个：真实对话 + 工具调用成功；无 key 的 preset 至少 golden 测试通过。
- [ ] 默认 ASK 模式下，模型要 `rm -rf` 被 CommandGuard 拦且用户不能批准；写项目外路径被 PathGuard 拦；正常 edit 弹 diff 审批，`a` 后同工具不再问。
- [ ] `/memory` 四个命令可用；`remember` 一段偏好后新 session 的 system prompt 含它（`PAI_DEBUG_HTTP=1` 验证）。
- [ ] core 改动仅限 ADR 修订批准过的 SPI 变更。
- [ ] 报告 `docs/reports/M4-report.md`。
