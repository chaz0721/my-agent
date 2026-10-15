# M7 规格书：harness 加固（可观测、可恢复、按输入判定）

> 前置：M6 通过。本阶段不加功能，只把内核从"能跑"变成"可观测、可恢复、能讲清楚"。四个子项，每项都对应一个面试会被追问而目前答不好的点。
> 预计 ≤ 700 行主代码（含 M6 输入项约 +100）。两处 ADR 修订（0002、0003）主控已写入修订记录，实施须与之一致。授权 core 改动：`loop`（TerminationReasonEnum、ContextStage、AgentEvent 新分量、ToolRunner 分组）、`compaction`（拆 Stage）、`Tool` 接口默认方法；pai-tools（ShellLexer、白名单资源）；ext-hitl（7C、PLAN 改读 effect(input)、删 ReadOnlyCommands）；cli（Ctrl+C 退出路径、SessionEntriesImpl 增量解析、bench 接线）。

## 来自 M6 的输入（已裁决，见 DECISIONS-LOG 与 ADR 0002/0003 修订记录）
- 压缩分级（微压缩两档 + 滞回 → 全量摘要）已存在于 `CompactingHooks` 内部（微压缩在 `prepareRequest` 做请求投影，`transformContext` 只判断），7A 把它提升为 loop 可见的管线阶段。**拆分时必须保留 M6 的三条修复语义**：投影估算只扣"本次新裁、上次未裁"的节省量且 `sentTrimmed` 未知时按已裁剪处理；第二档滞回到下一次全量摘要；摘要失败退避 3 turn、force 路径不受限、`/clear` 后 `reset()`。现有 `CompactingHooksMicroTest` 全部用例必须在新结构下继续通过。
- **事件双发**：M6 用 `CompactingHooks.withListener` 旁路给扩展宿主发带 summary 的 `ContextCompacted`，loop 另发一个不带 summary 的；MICRO 事件到不了 renderer（`EventView` 的 MICRO 分支是死代码）。7A 改为 loop 独家发出（阶段返回值携带 kind/summary），删除 `withListener`；ext-memory 的候选收集改为监听 loop 事件，不得重复收集。
- **压缩后不重读即可改**未达成：base prompt "先读再改" + edit 需逐字 oldText。7D 文件恢复是解法；不改 base prompt 规则。恢复内容应足以让 `edit` 的 oldText 命中（回灌当前文件内容，不是摘要）。
- `SummaryValidator` 的 write 工具名（`write`/`edit`）硬编码 → 7B 后改为 `tool.effect(input) == WRITE_FS` 判断（通过 `ToolRegistry` 查工具）。
- `SessionEntriesImpl` 缓存稳态几乎不命中（每次 MessageEnd 都移动 leaf）→ 7D 基线测出数字后，改按文件长度增量解析（只读新追加字节）；归入 7D 的 cli 授权改动。
- Ctrl+C 空闲退出不 dispatch `SessionEnded`，记忆候选静默丢失 → 7A 顺手：空闲 Ctrl+C 走正常退出路径（dispatch 后再 exit），属 cli 改动。
- `requestOverhead` 不含 `withToolsRegisteredSince` 补进的工具 → 7A 一并修正。

## 7A. 每轮管线显式化 + 终止原因（ADR 0003 修订）

目标：loop 每轮做什么、为什么结束，用类型说清楚。

```java
package dev.pai.core.loop;
public enum TerminationReasonEnum { COMPLETED, MAX_TURNS, ABORTED, PROMPT_TOO_LONG, MAX_OUTPUT_TOKENS, PROVIDER_ERROR, HOOK_ERROR }
// AgentEvent.AgentEnd 新增分量 reason（保留旧构造器，默认 COMPLETED）；AgentLoop.run 返回值不变。

public interface ContextStage {            // 新 SPI（封闭：只有 core 内两实现 + 测试 Fake）
  String name();
  /** 返回新列表表示本阶段生效；未生效返回同一实例。生效后后续阶段仍会被调用但应 no-op。 */
  List<Message> apply(List<Message> messages, LoopState state);
}
```
- `AgentLoop` 每轮固定顺序：`transformContext` 钩子链 → 阶段列表（`MicroCompactStage`、`SummaryCompactStage`，由 `CompactingHooks` 拆出）→ 预算检查 → provider → 工具 → `steeringMessages` → 终止判定。阶段列表由 `AgentConfig.stages()` 提供（默认两段），扩展**不能**注册阶段（避免成为第二套钩子）。
- `MAX_OUTPUT_TOKENS`（`StopReasonEnum.LENGTH` 且有工具调用被截断）：恢复路径——以 "continue" user message 追加一次重试，最多 2 次；超过则以 `MAX_OUTPUT_TOKENS` 结束。
- 连续压缩失败 3 次 → 熔断：停止自动压缩，notify，`/compact` 手动仍可用（计数随手动成功清零）。
- 每轮发出 `AgentEvent.TurnStart` 时附带 `turnIndex` 与估算 token（新增分量 `estimatedTokens`，保留旧构造器）；TUI 状态行 ctx% 用它。
- 测试：每种终止原因各一个用例；阶段顺序与 no-op 语义；MAX_OUTPUT_TOKENS 重试两次后终止；熔断计数。

## 7B. 工具元数据按输入判定（ADR 0002 修订）

目标：`bash ls` 与 `bash rm` 不该是同一个效果等级；并行调度与 PLAN/AUTO_READ 据此更精确。

```java
public interface Tool {
  ToolDefinition definition();
  ToolEffectEnum effect();                                        // 静态上限（保留）
  default ToolEffectEnum effect(JsonNode input) { return effect(); }   // 按输入细化，只能更"安全"不能更"危险"
  default boolean concurrencySafe(JsonNode input) { return effect(input) == ToolEffectEnum.READ_ONLY; }
  ToolResult execute(JsonNode arguments, ToolContext ctx) throws Exception;
}
```
- `BashTool.effect(input)`：命令经 **词法解析**（新 package-private `ShellLexer`：处理引号、转义、`;` `&&` `||` `|` `$()` 反引号、重定向）后，全部简单命令都在只读白名单且无重定向/替换 → `READ_ONLY`，否则 `EXECUTE`。白名单表从 ext-hitl 的 `ReadOnlyCommands` **下沉到 pai-tools 资源文件**（规则本身属于工具的自描述，不属于审批策略）；ext-hitl 的 PLAN 模式改为读 `tool.effect(input)`，`ReadOnlyCommands` 删除。
- `ToolRunner` 并行分组改用 `concurrencySafe(input)`；核心规则不变：连续安全调用并行，不安全串行。
- 保护：`effect(input)` 抛异常或返回比 `effect()` 更危险的值 → 取 `effect()`（fail-closed），warn。
- 测试：`ShellLexer` 表驱动（M5 审查的全部绕过样例必须判为 EXECUTE）；`ls | head` READ_ONLY、`ls > x` EXECUTE、`cat ~/.ssh/x`（PLAN 转审批逻辑仍在 hitl，用 `effect(input)` + 路径检测）；并行分组按输入。

## 7C. 审批 `always` 持久化 + deny 规则

目标：日常体验最大的缺口；对应 Claude Code 的 `permissions.allow/deny`。

- `~/.pai/ext/hitl/permissions.json`：`{"allow":[{"tool":"bash","pattern":"mvn *"},{"tool":"edit"}],"deny":[{"tool":"bash","pattern":"git push*"}]}`；项目级 `<cwd>/.pai/permissions.json` 仅受信任时合并（deny 可叠加，allow 不能放宽用户级 deny）。
- 审批提示增加选项 `A`（always，持久化）：bash 以首个 token + `*` 生成 pattern 让用户确认后写入；其他工具按工具名。会话内 `a` 不变。
- deny 命中 → 像 Guard 一样不可批准，并在 `prepareRequest` 时从请求工具列表剔除（工具级 deny）或在 beforeToolCall 拦（pattern 级）。
- `/approvals list|allow <tool> [pattern]|deny …|forget <n>`。
- 测试：pattern 匹配（glob 风格，大小写不敏感 Windows）、项目级不能放宽用户级 deny、持久化读写、未信任项目忽略项目级。

## 7D. 压缩后文件恢复 + 性能基线

- 全量摘要成功后，从 `## Files touched` 取最近 ≤5 个仍存在的文件，各读 ≤ 5k token 回灌为一条 user 消息 `<restored-files>`（总 ≤ 25k token，不计入摘要预算）。无 `Files touched` 节则不恢复。测试：恢复数量上限、不存在文件跳过。
- **性能基线脚本** `build/bench.ps1`（Windows）/`build/bench.sh`：FakeProvider 下测 (1) 冷启动到提示符，(2) 1000 条 entry 的 session `--resume` 加载，(3) 一次 `read` 工具的 loop 开销（不含模型），(4) 微压缩 200 条消息耗时，(5) 全量摘要前后 token 估算。结果写 `docs/reports/bench-M7.md`。不设门槛，只建立数字——面试被问"性能"时有据可答。

## 不做
平台沙箱（seatbelt/bwrap/AppContainer）、旁路分类模型、工具结果落盘、多会话 work stealing。

## 验收
- [ ] `AgentEnd.reason` 在 7 种情形下各正确一次（FakeProvider 脚本）。
- [ ] 真实 DeepSeek：PLAN 下 `bash ls -R | head` 免审、`bash echo x > f` 被拦、`bash cat ~/x` 转审批——全部由 `effect(input)` 驱动，`ReadOnlyCommands` 已删除。
- [ ] `A` 持久化 `bash: mvn *` 后新会话不再询问；`deny git push*` 不可批准。
- [ ] 8k 窗口任务：压缩后模型未重读即能继续改 `Files touched` 里的文件。
- [ ] `docs/reports/bench-M7.md` 有五项数字；`docs/reports/M7-report.md`。
- [ ] core 改动仅限 7A/7B 授权项；ADR 0002/0003 修订由主控写。
