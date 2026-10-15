# ADR 0003：Loop 只产出事件，不碰终端，可扩展点是有限的钩子集

状态：已接受　日期：2026-10-14

## 背景

旧 `Agent.java` 1339 行里没有 loop（loop 在 `DefaultAgentLoop`），有的是：290 行 `StreamRenderer` 终端渲染、按工具名硬编码的输出格式（`web_search` emoji）、新旧结果类型互转。`ToolRegistry` 里也混着输出格式。渲染和控制流纠缠，导致三套渲染器各自有 bug。

## 决定

`AgentLoop.run()` 是一个纯控制流：

```
loop:
  emit TurnStart
  assistant = provider.stream(request, ev -> emit MessageUpdate(ev))
  emit MessageEnd(assistant)
  if no ToolCall blocks → emit TurnEnd; break
  for each ToolCall (并行规则见 ADR 0002):
     decision = hooks.beforeToolCall(call)      // ALLOW | BLOCK(reason)
     result   = decision.allowed ? tool.execute(...) : ToolResult.error(reason)
     result   = hooks.afterToolCall(call, result)
     emit ToolExecutionStart/End; append ToolResultMessage
  emit TurnEnd
  messages = hooks.transformContext(messages)   // 压缩在这里发生
```

```java
public sealed interface AgentEvent permits
  AgentStart, AgentEnd, TurnStart, TurnEnd,
  MessageStart, MessageUpdate /* 包一个 model.StreamEvent */, MessageEnd,
  ToolExecutionStart, ToolExecutionUpdate, ToolExecutionEnd,
  ContextCompacted, Error {}

public interface AgentHooks {
  default ToolDecision beforeToolCall(ToolCall call, Tool tool, LoopState state) { return ToolDecision.allow(); }
  default ToolResult afterToolCall(ToolCall call, ToolResult result, LoopState state) { return result; }
  default List<Message> transformContext(List<Message> messages, LoopState state) { return messages; }
  default List<Message> steeringMessages() { return List.of(); }   // 运行中插入用户消息
}
```

- Loop 不 import 任何 tui/cli 类型；不知道"终端"存在。
- Loop 对工具名**无感知**：没有 `if (name.equals("web_search"))`。工具专属展示归 tui 的 renderer 注册表。
- 钩子集是**有限枚举**（上面 4 个 + M3 可能加 `prepareRequest`），不是"把 loop 内部对象递出去"。扩展只能看到 `LoopState` 只读视图（messages 快照、当前 model、本轮 usage）。
- 取消通过 `CancelToken`（协作式），工具和 provider 都必须检查。

## 后果

- TUI、无头 JSON 输出、RPC 模式都是同一个事件流的不同消费者。
- 测试：用 `FakeProviderImpl` 产事件即可端到端测 loop，不需要网络和终端。
- 代价：想做"奇怪的事"（比如在 loop 中途换模型）只能通过钩子的返回值，不能直接改 loop 状态。这是故意的。

## 修订记录

- 2026-10-15（M2）：新增第 5 个钩子 `default ChatRequest prepareRequest(ChatRequest req, LoopState state)`，每次 provider 调用前、`transformContext` 之后调用，链式按注册顺序折叠；抛异常的钩子被跳过（请求原样交给下一个钩子），其余钩子照常生效。用途：skill 索引注入 system prompt、M4 审批提示、provider 特殊参数。它只改本次请求，不改保留的上下文。同期 `LoopState` 新增只读 `contextOverflow()`（prompt-too-long 的那一次重试为 true，压缩钩子据此强制压缩）与 `cancel()`（供摘要等慢钩子协作取消）；loop 在 `transformContext` 返回的上下文长度不同、或任一元素引用不同时发出 `ContextCompacted`（钩子不压缩时须原样返回传入的列表）。
- 2026-10-15（M3）：`LoopState` 新增 `default Optional<String> leafEntryId()`：宿主持久化会话时，当前上下文所投影的 session entry id（宿主通过 `AgentLoop.withLeaf(Supplier)` 提供，每次取快照时在 loop 线程询问）；`CompactingHooks` 据此定位 `firstKeptEntryId` 并把压缩 entry 挂在该 leaf 下，`CompactingHooks.Target` 只剩 `session`。
- 2026-10-15（M6）：`AgentEvent.ContextCompacted` 新增分量 `kind`（`KindEnum{MICRO, SUMMARY}`）与 `Optional<String> summary`，保留 2 参构造器（默认 SUMMARY、无摘要）。微压缩是**请求投影**：在 `prepareRequest` 中把早期大块 `ToolResult` 替换为占位，只改本次请求，不改保留的上下文、不写 session entry（放在 `transformContext` 会使上下文与 session 投影的逐条 equals 对齐失败、后续全量摘要全部被跳过，并触发 Repl 重置 leaf）。已知漏洞：loop 发出的 `ContextCompacted` 不含 kind/summary，带摘要的事件目前由 `CompactingHooks.withListener` 旁路发给扩展宿主，renderer/无头消费者收不到 MICRO 事件——与"同一事件流"原则不一致，M7 7A 管线显式化时由 loop 独家发出该事件并废除旁路。
- 2026-10-15（M7）：**每轮管线显式化 + 终止原因**。(1) 新增 `TerminationReasonEnum { COMPLETED, MAX_TURNS, ABORTED, PROMPT_TOO_LONG, MAX_OUTPUT_TOKENS, PROVIDER_ERROR, HOOK_ERROR }`，`AgentEvent.AgentEnd` 新增分量 `reason`（保留旧构造器，默认 COMPLETED）；`AgentLoop.run` 返回值不变。(2) 新增**封闭** SPI `ContextStage { String name(); List<Message> apply(List<Message>, LoopState); }`——只有 core 内 `MicroCompactStage`、`SummaryCompactStage`（由 `CompactingHooks` 拆出）与测试 Fake 实现，由 `AgentConfig.stages()` 提供，扩展**不能**注册阶段（避免成为第二套钩子）。每轮固定顺序：`transformContext` 钩子链 → 阶段列表 → 预算检查 → provider → 工具 → `steeringMessages` → 终止判定；阶段"未生效返回同一实例"，生效后后续阶段仍被调用但应 no-op。(3) `ContextCompacted` 改由 **loop 独家发出**，阶段返回值携带 kind 与 summary（阶段生效即发一次对应 kind 的事件），废除 M6 的 `CompactingHooks.withListener` 旁路；renderer、无头 JSON 等所有消费者看到同一事件流（修复 M6 已知漏洞）。微压缩阶段仍是请求投影：它生效时 loop **不**替换保留的上下文，只把投影交给 provider 请求。(4) `TurnStart` 新增分量 `estimatedTokens`（保留旧构造器），TUI 状态行 ctx% 用它。(5) `MAX_OUTPUT_TOKENS`（`StopReasonEnum.LENGTH` 且工具调用被截断）：追加一条 "continue" user message 重试，最多 2 次，超过以该原因结束。(6) 连续压缩失败 3 次熔断：停止自动压缩、notify，`/compact` 手动仍可用，手动成功清零。
