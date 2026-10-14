# pi (TypeScript) → pai-next (Java) 类型映射约定

读 pi 源码时按这张表翻译**概念**，不翻译代码。pi 比我们大很多（classifier、image 生成、deferred、OAuth、多种 API 形态），只取 00-architecture.md 列的子集。

## 通用映射

| TS 形态 | Java 形态 | 备注 |
|---|---|---|
| discriminated union `type: "a" \| "b"` | `sealed interface` + `record` 实现，Jackson `@JsonTypeInfo(property="type")` | 子类名用名词，json 名用 snake_case |
| `interface Foo { x?: T }` | `record Foo(Optional<T> x)` 或 `OptionalInt` | 不用 null；集合用空集合不用 Optional |
| `Record<string, unknown>` / `JsonObject` | `JsonNode`（Jackson） | 只在边界（工具参数、providerExtras、CustomEntry.data）出现 |
| `AbortSignal` | `CancelToken` | 协作式；provider 与工具都要检查 |
| `AsyncIterable<Event>` / EventStream | `Consumer<Event>` 同步回调 + 阻塞返回最终值 | M1 不引入 Reactor/虚拟线程流；虚拟线程可用于工具并行执行 |
| `timestamp: number`(ms) | `Instant` | JSON 用 ISO-8601 |
| `string & {}` 开放枚举（Api/ProviderId） | `String` + 常量类 | 不用 enum，便于扩展注册新 provider |
| `TSchema`(typebox) | `JsonNode` 手写/`JsonSchema` builder | 不引 schema 库；校验只做 required+类型 |

## pi-ai → pai-model

| pi `packages/ai/src/types.ts` | pai-model | 取舍 |
|---|---|---|
| `Message = SystemMessage \| UserMessage \| AssistantMessage \| ToolResultMessage` | 同名四 record | ✔ |
| `SystemMessage.sections / toolsAdded / toolsRemoved`（中途改提示词/工具） | **不取** | 我们 system prompt 每次请求重建，工具集全程固定 |
| `TextContent / ThinkingContent / ImageContent / ToolCall` | `ContentBlock.Text/Thinking/Image/ToolCall` | `ToolCall.arguments: JsonObject` → `JsonNode`；`thinkingSignature`/`thoughtSignature` 合并为 `Thinking.signature` |
| `Usage{input, output, cacheRead, cacheWrite, cost}` | `Usage` 四字段 | cost **不取**（定价表是 UI 层的事） |
| `StopReason` 含 `pending/deferred` | 五值枚举 | deferred 不取 |
| `AssistantMessageEvent`（start/…_start/_delta/_end/done/error，带 `partial`） | `StreamEvent` 八种 | `partial` 快照不取（消费者自己累积）；`*_start/_end` 文本/思考块的 start/end 不取，只保留 ToolCallStart/End |
| `Tool{name, description, parameters}` | `ToolDefinition` | `constrainedSampling` 不取 |
| `Model{id, api, provider, baseUrl, contextWindow, maxTokens, reasoning, input[], cost, compat}` | `ModelRef` + `CapabilityEnum` 集合 | compat 细节 → `ProviderFlags`（M4） |
| `Context{systemPrompt, messages, tools}` → `normalizeContext` | `ChatRequest` | 不做 branded type |
| `StreamOptions`（temperature/maxTokens/samplingParams/cacheRetention/…） | `ChatOptions` + `providerExtras` | 只取四个 |
| `api/openai-completions.ts`、`api/anthropic-messages.ts` | 两个 Codec + 两个 Provider | 读它们的 SSE 分支，特别是 tool-call 增量与 thinking signature 回传 |

## pi-agent → pai-core.loop

| pi `packages/agent/src/types.ts` | pai-core | 取舍 |
|---|---|---|
| `AgentTool{label, execute(toolCallId, params, signal, onUpdate), executionMode}` | `Tool{definition, effect, execute(args, ctx)}` | `executionMode` 由 `effect()` 推导；`label` 归 tui |
| `AgentToolResult{content, details, isError, terminate}` | `ToolResult{content, isError, details}` | `terminate` 不取 |
| `beforeToolCall → {block, reason}` / `afterToolCall` | `AgentHooks.beforeToolCall → ToolDecision` / `afterToolCall` | ✔ |
| `transformContext` / `convertToLlm` | `transformContext` | `convertToLlm` 不需要（我们没有自定义 AgentMessage） |
| `getSteeringMessages / getFollowUpMessages` | `steeringMessages()` | follow-up 不取 |
| `prepareRequest / finishTurn / prepareNextTurn` | M2 加 `prepareRequest`；其余不取 | |
| `AgentEvent`（agent_/turn_/message_/tool_execution_*） | `AgentEvent` 同形 | ✔ 一一对应 |
| `AgentState`（可变 messages/tools/model） | **不取** | 状态由 cli 持有：history + session；loop 无状态，`run()` 纯函数风格 |
| `agent-loop.ts` 并行执行：preflight 顺序、执行并行、结果按源顺序 | 同规则 | ✔ |

## pi coding-agent → pai-core.session

| pi `session-manager.ts` | pai-core.session | 取舍 |
|---|---|---|
| `SessionHeader{type:"session", version, id, cwd, timestamp, parentSession}` | `SessionHeader` | `parentSession` M5 子代理时加 |
| `message / model_change / thinking_level_change / usage / compaction / branch_summary / label / custom / custom_message / context_edit / session_info` | 取 `message / model_change / compaction / label / custom` 五种 | 其余需要时再加（加 entry 类型不算 SPI 变更） |
| `parentId` 树 + `/tree` `/fork` | 同 | ✔ |
| `buildSessionContext`（投影：最近 compaction 起 + 替换） | `buildContext` | ✔ 简化版 |
| 文件位置 `~/.pi/agent/sessions/<cwd-encoded>/` | `~/.pai/sessions/<cwd-hash>/` | |

## pi 扩展 API → pai-core.ext

pi 的 `ExtensionAPI` 有 ~40 个方法和 ~30 种事件。我们取：
`registerTool / registerCommand / registerProvider / on(event)` + hooks（`addHooks`），事件只暴露 `AgentEvent` 与 `SessionStart/End`。UI 相关（`setStatus/setWidget/setFooter/ui.select…`）**全部不取**，直到 M4 审批需要时以最小 `UserInteraction` 接口引入（见 M4 规格 4B）。
