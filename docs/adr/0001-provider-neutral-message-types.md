# ADR 0001：消息类型 provider 中立

状态：已接受　日期：2026-10-14

## 背景

旧仓库 `LlmClient.Message` 的字段就是 OpenAI Chat Completions 的 wire format（`role/content/tool_calls/tool_call_id`，`ToolCall.function.arguments` 是字符串）。所有上层——压缩、持久化、session 重建、渲染——都直接依赖这个形状。结果：
- 整个代码库零 Anthropic 代码；加原生 Anthropic 需要改每一层。
- `reasoning_content`、cache usage 等字段靠 6 个 vendor 特判塞进同一个类。
- 图片输入要在序列化时按 provider 替换成文字提示（DeepSeek）。

## 决定

`pai-model` 定义**与任何 wire format 无关**的类型，provider 通过 `Codec` 双向翻译：

```java
public sealed interface Message permits SystemMessage, UserMessage, AssistantMessage, ToolResultMessage {}

public record SystemMessage(String text) implements Message {}
public record UserMessage(List<ContentBlock> content, Instant timestamp) implements Message {}
public record AssistantMessage(
    List<ContentBlock> content,          // Text | Thinking | ToolCall
    String providerId, String modelId,
    Usage usage, StopReasonEnum stopReason,
    Optional<String> errorMessage,
    Instant timestamp) implements Message {}
public record ToolResultMessage(
    String toolCallId, String toolName,
    List<ContentBlock> content,          // Text | Image
    boolean isError, Instant timestamp) implements Message {}

public sealed interface ContentBlock permits Text, Thinking, Image, ToolCall {
  record Text(String text) implements ContentBlock {}
  record Thinking(String text, Optional<String> signature) implements ContentBlock {}   // signature: provider 不透明回传数据
  record Image(String base64, String mimeType) implements ContentBlock {}
  record ToolCall(String id, String name, JsonNode arguments) implements ContentBlock {}  // arguments 永远是已解析 JSON 对象
}
```

- `ToolCall.arguments` 是 `JsonNode`（对象），不是字符串。流式阶段的部分 JSON 由 Codec 在 `ToolCallEnd` 前拼好。
- `Thinking.signature` 是 provider 专属的不透明数据（Anthropic 的 signature、DeepSeek/Kimi 的 `reasoning_content` 回传需求都落在这里），Codec 负责在下一轮请求时放回正确位置。
- provider 不支持的内容（DeepSeek 不支持图片）由该 provider 的 Codec 决定降级策略，并通过 `ModelRef.capabilities` 提前声明。

## 后果

- 新增 provider = 新增一个 `Codec` + 一个 `ModelRef` 预设，不碰其他模块。
- 持久化（JSONL）直接序列化这些 record，与 provider 无关，换模型不影响 session 可读性。
- 代价：每个 provider 要写两个方向的翻译。接受。

## 修订记录

- 2026-10-15（M1 审查）：`Thinking.signature` 允许承载 provider 私有数据（Anthropic signature、DeepSeek 的来源字段标记），但 **Codec 回放时必须校验 `AssistantMessage.providerId` 与当前请求一致**，不一致则不回放该 thinking 块。否则跨 provider `--resume` 会被 API 拒绝。另：`stopReason` 为 ERROR/ABORTED 的 assistant 消息在回放时整条跳过（其中的 ToolCall 没有对应 result）。
