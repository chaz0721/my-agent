# M1 规格书：地基

> 实施前必读：`../00-architecture.md`、`../adr/0001`–`0005`、`../reference/pi-type-mapping.md`。
> 本阶段的公开签名是合同。可以加私有/包私有的东西，不能改公开签名。觉得不对 → 停下来写进报告。

## 目标

从零建 `pai-next` 多模块工程，用 **Anthropic Messages** 和 **一个 OpenAI Chat Completions 兼容端点**（建议 DeepSeek 或 GLM，取决于有哪个 key）跑通：终端输入 → 流式输出 → 模型调用 read/write/edit/bash 四个工具 → 结果回灌 → 完成；整轮对话落 JSONL，重启后 `--resume` 能接着聊。

## 范围

**做**：
- Maven 多模块：`pai-parent` + `pai-model` / `pai-core` / `pai-tools` / `pai-cli`。Java 21。**第一步**先接 Spotless（p3c 模板）、ArchUnit、Enforcer、Surefire 默认开、P3C 报告型，让空工程 `mvn verify` 通过，再写业务代码。
- `pai-model`：全部类型、`Provider` SPI、两个 Codec、SSE 传输、`ProviderRegistry`。
- `pai-core`：Tool 契约与 Registry、AgentLoop + AgentEvent + AgentHooks、SessionStore(JSONL)、Extension SPI（ServiceLoader）、最简 system prompt 拼装。
- `pai-tools`：`BuiltinToolsExtension` 注册 read/write/edit/bash。
- `pai-cli`：无 JLine 的最简 REPL（`System.in` 逐行），把 AgentEvent 打到 stdout（文本 delta 直出、工具调用一行摘要）。支持 `--model provider/model`、`--resume`、`/quit`。
- ArchUnit 依赖规则测试；FakeProviderImpl 端到端 loop 测试；两个 Codec 的录制回放测试；SessionStore 测试。
- `build/check-budgets.sh`（行数 + TODO）接进 `verify`；CI 用 `build/ci/verify.yml.example`。`--provider fake` 内置 `FakeProviderImpl` 供 CliSmokeTest 用。
- ADR 五份已存在；本阶段**不改** ADR。

**不做**（后续阶段）：JLine/TUI、压缩、skill、MCP、审批/HITL、记忆、重试/断路器/fallback、多模型切换 UI、`/tree`/fork UI、国产 provider 预设表（只需 OpenAI-compat 通用实现 + 命令行给 base URL/key）。

## 模块与包布局

```
pai-next/
  pom.xml                               dev.pai:pai-parent（packaging pom；dependencyManagement；java 21；surefire 默认跑测试）
  pai-model/   dev.pai.model, dev.pai.model.codec.openai, dev.pai.model.codec.anthropic, dev.pai.model.http
  pai-core/    dev.pai.core.tool, dev.pai.core.loop, dev.pai.core.session, dev.pai.core.ext, dev.pai.core.prompt, dev.pai.core.internal.*
  pai-tools/   dev.pai.tools
  pai-cli/     dev.pai.cli
  docs/        （已存在）
```

依赖：Jackson 2.17+（databind, jsr310），JUnit 5.10+，AssertJ，ArchUnit 1.3+（pai-cli test scope），OkHttp **不要**——用 JDK `java.net.http.HttpClient`（支持 HTTP/1.1 强制、流式 body、取消）。日志按阿里手册用 SLF4J 门面：核心模块只依赖 `slf4j-api` 2.x，绑定（`slf4j-simple` 或 logback）只在 `pai-cli`。

## pai-model 合同

### 消息类型（见 ADR 0001，此处为权威签名）

```java
package dev.pai.model;

public sealed interface Message permits SystemMessage, UserMessage, AssistantMessage, ToolResultMessage {}
public record SystemMessage(String text) implements Message {}
public record UserMessage(List<ContentBlock> content, Instant timestamp) implements Message {
  public static UserMessage text(String s);
}
public record AssistantMessage(List<ContentBlock> content, String providerId, String modelId,
                               Usage usage, StopReasonEnum stopReason, Optional<String> errorMessage,
                               Instant timestamp) implements Message {
  public List<ContentBlock.ToolCall> toolCalls();
  public String text();                      // 所有 Text 块拼接
}
public record ToolResultMessage(String toolCallId, String toolName, List<ContentBlock> content,
                                boolean isError, Instant timestamp) implements Message {}

public sealed interface ContentBlock permits ContentBlock.Text, ContentBlock.Thinking, ContentBlock.Image, ContentBlock.ToolCall {
  record Text(String text) implements ContentBlock {}
  record Thinking(String text, Optional<String> signature) implements ContentBlock {}
  record Image(String base64, String mimeType) implements ContentBlock {}
  record ToolCall(String id, String name, JsonNode arguments) implements ContentBlock {}
}

public record Usage(long input, long output, long cacheRead, long cacheWrite) {
  public static final Usage ZERO; public Usage plus(Usage o); public long total();
}
public enum StopReasonEnum { STOP, LENGTH, TOOL_USE, ERROR, ABORTED }

public record ToolDefinition(String name, String description, JsonNode parametersSchema) {}

public record ModelRef(String providerId, String modelId, URI baseUrl, int contextWindow, int maxOutputTokens,
                       Set<CapabilityEnum> capabilities) {
  public enum CapabilityEnum { TOOLS, IMAGE_INPUT, THINKING, PROMPT_CACHE }
}

public record ChatRequest(ModelRef model, Optional<String> systemPrompt, List<Message> messages,
                          List<ToolDefinition> tools, ChatOptions options) {}
public record ChatOptions(OptionalInt maxTokens, OptionalDouble temperature, ThinkingLevelEnum thinking,
                          Map<String, JsonNode> providerExtras) {           // providerExtras: 原样合入请求体，供 top_p 等
  public enum ThinkingLevelEnum { OFF, LOW, MEDIUM, HIGH }
  public static ChatOptions defaults();
}
```

Jackson 序列化：所有 record 必须可无损 round-trip（`@JsonTypeInfo` 用 `type` 字段区分 sealed 子类：`"text"/"thinking"/"image"/"tool_call"`；`"system"/"user"/"assistant"/"tool_result"`）。提供 `dev.pai.model.Json.mapper()` 单例，已注册 jsr310 与 `Optional` 模块。

### 流事件协议

```java
public sealed interface StreamEvent permits Start, TextDelta, ThinkingDelta, ToolCallStart, ToolCallDelta, ToolCallEnd, Done, Error {
  record Start() implements StreamEvent {}
  record TextDelta(int index, String delta) implements StreamEvent {}
  record ThinkingDelta(int index, String delta) implements StreamEvent {}
  record ToolCallStart(int index, String id, String name) implements StreamEvent {}
  record ToolCallDelta(int index, String argumentsJsonDelta) implements StreamEvent {}
  record ToolCallEnd(int index, ContentBlock.ToolCall toolCall) implements StreamEvent {}   // arguments 已解析
  record Done(AssistantMessage message) implements StreamEvent {}
  record Error(StopReasonEnum reason /* ERROR|ABORTED */, String message, Optional<AssistantMessage> partial) implements StreamEvent {}
}
```

协议约束（写进 Javadoc 和测试）：`Start` 必须最先；`Done` 或 `Error` 必须最后且只出现一次；`index` 是 content 块下标，同一块的 delta 单调；`ToolCallEnd.toolCall.arguments` 必须是合法 JSON 对象，不合法则该块转为 `Error`? **不**——转为一个 `ToolCall` 其 `arguments` 为 `{}` 并在 `Done.message.errorMessage` 记一条诊断，让 loop 把错误回灌给模型（模型可以自我修正）。

### Provider SPI

```java
public interface Provider {
  String id();                                           // "anthropic" | "openai-compat" | ...
  Set<ModelRef.CapabilityEnum> capabilities(ModelRef model);
  /** 阻塞直到流结束；每个事件同步回调；返回最终 AssistantMessage（与 Done/Error 里一致）。
   *  不得抛出 request/网络错误——编码为 Error 事件 + stopReason ERROR 返回。参数错误（如 model 为 null）可以抛 IllegalArgumentException。 */
  AssistantMessage stream(ChatRequest request, Consumer<StreamEvent> sink, CancelToken cancel);
}

public interface CancelToken {                            // 在 pai-model，core 复用
  boolean isCancelled(); void onCancel(Runnable r);
  static CancelToken none();
}

public final class ProviderRegistry {                     // 简单 map，含 register/get/ids
}
```

### Codec（纯函数，可离线测试）

```java
package dev.pai.model.codec.openai;
public final class OpenAiCompletionsCodec {
  public ObjectNode toRequestBody(ChatRequest req);                       // stream:true, tools[], messages[] 翻译；Thinking 块：若 signature 存在且 provider 需要（DeepSeek/Kimi）→ 写回 reasoning_content
  public StreamDecoder newDecoder();                                     // 有状态：喂 SSE data 行，产 StreamEvent
  public interface StreamDecoder { List<StreamEvent> feed(String sseDataLine); List<StreamEvent> finish(); }
}
package dev.pai.model.codec.anthropic;
public final class AnthropicMessagesCodec { /* 同形状；system 独立字段；tool_use/tool_result 块；thinking 块 signature 回传 */ }
```

**OpenAI 侧必须正确处理**（旧仓库 `AbstractOpenAiCompatibleClient` 已踩过的坑，去读它 :111-220, :251-301, :415-460，然后重写）：
- `delta.tool_calls[].index` 为键累积 `id/function.name/function.arguments`；`finish_reason` 可能在最后一个无 delta 的 chunk。
- `reasoning_content` / `reasoning` / `reasoning_details[].text` 三种名字 → `ThinkingDelta`。
- usage 可能在最后一个 chunk（`stream_options.include_usage`），cache 字段名有 `prompt_tokens_details.cached_tokens`、`prompt_cache_hit_tokens` 等，全部映射到 `Usage.cacheRead`。
- `[DONE]` 哨兵；非 JSON 行忽略；`error` 对象 → `Error`。
- `ToolCall.arguments` 为空字符串时解析为 `{}`。

**Anthropic 侧**：`message_start`（usage.input, cache_*）、`content_block_start/delta/stop`（`text_delta`/`thinking_delta`/`input_json_delta`/`signature_delta`）、`message_delta`（stop_reason, usage.output）、`message_stop`；`ping` 忽略；`error` 事件 → `Error`。stop_reason 映射：`end_turn→STOP`，`max_tokens→LENGTH`，`tool_use→TOOL_USE`。

### HTTP 传输

```java
package dev.pai.model.http;
public final class SseClient {
  /** POST JSON，逐行读 body；每个 "data: ..." 行回调；支持 cancel 中断；超时可配；forceHttp1 选项（DeepSeek 需要）。 */
  public void postStream(URI uri, Map<String,String> headers, byte[] body, Consumer<String> onDataLine, CancelToken cancel) throws IOException;
}
public final class AnthropicProviderImpl implements Provider { /* 构造：apiKey, baseUrl, SseClient；header anthropic-version */ }
public final class OpenAiCompatProviderImpl implements Provider { /* 构造：id, apiKey, baseUrl, SseClient, flags(forceHttp1, echoReasoning) */ }
```

Provider 不做重试（M4 考虑放到扩展或 cli 层）。非 2xx：读完 body，发 `Error(ERROR, "HTTP 429: <body 前 500 字>")`。

## pai-core 合同

### tool

```java
package dev.pai.core.tool;
public interface Tool {
  ToolDefinition definition();
  ToolEffectEnum effect();
  default boolean concurrencySafe() { return effect() == ToolEffectEnum.READ_ONLY; }
  ToolResult execute(JsonNode arguments, ToolContext ctx) throws Exception;   // 抛异常 = isError 结果，消息为 e.getMessage()
}
public enum ToolEffectEnum { READ_ONLY, WRITE_FS, EXECUTE, NETWORK, UNKNOWN }
public record ToolResult(List<ContentBlock> content, boolean isError, Optional<JsonNode> details) {
  public static ToolResult text(String s); public static ToolResult error(String s);
}
public interface ToolContext { Path cwd(); CancelToken cancel(); void progress(String partial); }
public final class ToolRegistry {
  public void register(Tool t, String sourceId);   // 重名 → IllegalStateException
  public Optional<Tool> get(String name); public List<Tool> all(); public List<ToolDefinition> definitions();
}
public final class Args { /* Args.of(JsonNode): string(k), stringOr(k,d), intOr(k,d), boolOr(k,d), require(k...) → IllegalArgumentException 带字段名 */ }
public final class JsonSchema { /* 小 builder：object().string(name, desc).integer(..).bool(..).array(name, desc, itemType).required(..).build() → ObjectNode */ }
```

### loop

```java
package dev.pai.core.loop;
public sealed interface AgentEvent permits AgentStart, AgentEnd, TurnStart, TurnEnd, MessageStart, MessageUpdate, MessageEnd,
                                            ToolExecutionStart, ToolExecutionUpdate, ToolExecutionEnd, ContextCompacted, Error {
  record AgentStart() implements AgentEvent {}
  record AgentEnd(List<Message> newMessages) implements AgentEvent {}
  record TurnStart(int turnIndex) implements AgentEvent {}
  record TurnEnd(AssistantMessage message, List<ToolResultMessage> toolResults) implements AgentEvent {}
  record MessageStart(Message message) implements AgentEvent {}                 // user/toolResult 追加时；assistant 开始流时（空壳）
  record MessageUpdate(StreamEvent event) implements AgentEvent {}
  record MessageEnd(Message message) implements AgentEvent {}
  record ToolExecutionStart(ContentBlock.ToolCall call) implements AgentEvent {}
  record ToolExecutionUpdate(String toolCallId, String partial) implements AgentEvent {}
  record ToolExecutionEnd(ContentBlock.ToolCall call, ToolResult result, Duration took) implements AgentEvent {}
  record ContextCompacted(int messagesBefore, int messagesAfter) implements AgentEvent {}   // M1 定义，M2 才会发出
  record Error(String message, Optional<Throwable> cause) implements AgentEvent {}
}

public record ToolDecision(boolean allowed, Optional<String> reason) { static allow(); static block(String reason); }

public interface LoopState {            // 只读视图
  List<Message> messages(); ModelRef model(); Usage usageThisRun(); int turnIndex(); Path cwd();
}

public interface AgentHooks {
  default ToolDecision beforeToolCall(ContentBlock.ToolCall call, Tool tool, LoopState state) { return ToolDecision.allow(); }
  default ToolResult afterToolCall(ContentBlock.ToolCall call, ToolResult result, LoopState state) { return result; }
  default List<Message> transformContext(List<Message> messages, LoopState state) { return messages; }
  default List<Message> steeringMessages() { return List.of(); }
  static AgentHooks chain(List<AgentHooks> hooks);   // ADR 0005 语义：before 短路 BLOCK；after/transform 顺序折叠；steering 拼接
}

public record AgentConfig(ModelRef model, String systemPrompt, ChatOptions options, int maxToolConcurrency /*默认4*/, int maxTurns /*默认 50*/) {}

public final class AgentLoop {
  public AgentLoop(Provider provider, ToolRegistry tools, AgentHooks hooks, AgentConfig config, Path cwd);
  /** 以 messages 为既有上下文追加 prompt 并运行到无 tool call 或 maxTurns；事件同步回调；返回本次新增的全部 Message（含 assistant 与 toolResult）。 */
  public List<Message> run(List<Message> history, UserMessage prompt, Consumer<AgentEvent> sink, CancelToken cancel);
}
```

Loop 的算法见 ADR 0003。并行规则：同一 assistant 消息里的 tool calls，`concurrencySafe()` 全为 true 的一组并行（上限 `maxToolConcurrency`），否则按出现顺序串行；**`ToolResultMessage` 的追加顺序永远与 tool call 出现顺序一致**（事件 `ToolExecutionEnd` 可乱序）。工具抛异常 → `ToolResult.error`。模型请求的工具不存在 → `ToolResult.error("unknown tool: x")`。`Error` 事件后 loop 结束并返回已累积消息。

### session

```java
package dev.pai.core.session;
public record SessionId(String value) {}
public sealed interface SessionEntry permits MessageEntry, ModelChangeEntry, CompactionEntry, LabelEntry, CustomEntry {
  String id(); Optional<String> parentId(); Instant timestamp();
  record MessageEntry(String id, Optional<String> parentId, Instant timestamp, Message message) implements SessionEntry {}
  record ModelChangeEntry(String id, Optional<String> parentId, Instant timestamp, String providerId, String modelId) implements SessionEntry {}
  record CompactionEntry(String id, Optional<String> parentId, Instant timestamp, String summary, String firstKeptEntryId, long tokensBefore) implements SessionEntry {}
  record LabelEntry(String id, Optional<String> parentId, Instant timestamp, String targetId, String label) implements SessionEntry {}
  record CustomEntry(String id, Optional<String> parentId, Instant timestamp, String customType, JsonNode data) implements SessionEntry {}
}
public record SessionHeader(int version, SessionId id, Path cwd, Instant createdAt) {}

public interface SessionStore {
  SessionId create(Path cwd);
  SessionHeader header(SessionId id);
  String append(SessionId id, SessionEntry entry);                       // 返回 entry id；id 由 store 生成（caller 传 "" 时）或校验唯一
  List<SessionEntry> readAll(SessionId id);
  Optional<String> leafId(SessionId id);                                  // 最后追加的 entry
  List<Message> buildContext(SessionId id, String leafEntryId);          // 沿 parent 链回溯，遇 CompactionEntry：以 summary 作 UserMessage 替代 firstKeptEntryId 之前的消息
  Optional<SessionId> mostRecent(Path cwd);
  List<SessionId> list(Path cwd);
}
public final class JsonlSessionStoreImpl implements SessionStore { public JsonlSessionStoreImpl(Path rootDir /* ~/.pai/sessions */); }
```

文件名：`<yyyyMMdd-HHmmss>-<id>.jsonl`，目录 `rootDir/<sha256(cwd) 前 16 位>/`。读到损坏行：跳过并通过 SLF4J `log.warn` 记录，不抛。

### ext

```java
package dev.pai.core.ext;
public interface Extension { String id(); default void onLoad(ExtensionContext ctx) {} default void onUnload() {} }
public interface ExtensionContext {
  void registerTool(Tool tool); void unregisterTool(String name); void registerCommand(SlashCommand cmd); void registerProvider(Provider p);
  void addHooks(AgentHooks hooks); void on(Class<? extends AgentEvent> type, Consumer<AgentEvent> l);
  void onSession(Class<? extends SessionEvent> type, Consumer<SessionEvent> l);     // SessionEvent sealed: SessionStarted(id, resumed) | SessionEnded(id)
  Path configDir(); org.slf4j.Logger logger();
}
public interface SlashCommand { String name(); String description(); CommandResult run(String args, CommandContext ctx); }
public record CommandResult(String output, boolean quit) {}
public interface CommandContext { Optional<SessionId> session(); ModelRef model(); void println(String s); }
public final class ExtensionLoader { public List<Extension> loadFromServiceLoader(); public ExtensionHost install(List<Extension>, ...); }
```

`ExtensionHost`（`dev.pai.core.ext`，public final，构造器包私有，由 `ExtensionLoader.install` 创建）持有 registry/hooks 链/命令表/listeners；cli 从它取组装好的东西。单个扩展 `onLoad` 抛异常 → 该扩展禁用并 warn，不影响其他扩展。（2026-10-15 修订：原标 internal，与 00 §2 规则 3 冲突）

### prompt

```java
package dev.pai.core.prompt;
public final class SystemPromptBuilder {
  public SystemPromptBuilder base(String text); public SystemPromptBuilder section(String name, String text); public String build();
}
```
默认基础提示词放 `pai-core/src/main/resources/dev/pai/core/prompt/base.md`，**≤ 900 token**（英文），内容：你是终端 coding agent、工具使用原则（先读再改、edit 优于 write、bash 注意 cwd）、简洁输出。不写任务流程、不写人格。

## pai-tools 合同

`BuiltinToolsExtension implements Extension`，`id()="builtin-tools"`，注册：

| 工具 | effect | 参数 schema | 行为要点 |
|---|---|---|---|
| `read` | READ_ONLY | `path`(string, req), `offset`(int), `limit`(int, 默认 2000 行) | 相对 cwd；带行号 `N\tcontent`；超 limit 提示剩余；二进制/过大 → error |
| `write` | WRITE_FS | `path`, `content` (req) | 创建父目录；返回写入字节数 |
| `edit` | WRITE_FS | `path`, `oldText`, `newText` (req) | `oldText` 必须**唯一**匹配，0 或 >1 次 → error 并说明次数；返回替换后 ±3 行上下文 |
| `bash` | EXECUTE | `command` (req), `timeoutSeconds`(int, 默认 120) | `ProcessBuilder("bash","-lc",cmd)`（Windows: `cmd /c`，但 M1 以 bash 为主），cwd=ctx.cwd；合并 stdout/stderr；超时 kill 树；输出 >50KB 截尾并标注；非 0 退出码不算 isError，把 exit code 写进文本 |

路径逃逸 cwd **在 M1 不拦**（那是 ext-hitl 的事，M4），但 `read/write/edit` 要把解析后的绝对路径写进 `details`，供钩子使用。

## pai-cli 合同（M1 最简）

```
java -jar pai-cli.jar [--provider anthropic|openai-compat] [--model <id>] [--base-url <url>] [--resume [<sessionId>]] [--cwd <dir>]
环境变量：PAI_ANTHROPIC_API_KEY, PAI_OPENAI_COMPAT_API_KEY（或 --api-key）
```
- 启动：加载 ServiceLoader 扩展 → 建 Provider → 建/恢复 session → 打印一行 `model=… session=… cwd=…`。
- REPL：读一行；`/quit` 退出；`/model` 打印当前；其他以 `/` 开头查命令表，未知 → 打印 `unknown command`（不交给模型）；普通文本 → `loop.run(...)`。
- 渲染：`TextDelta` 直接 `print`；`ThinkingDelta` 不显示（或灰色，可选）；`ToolExecutionStart` 打一行 `▸ name(args 摘要 ≤80 字)`；`ToolExecutionEnd` 打一行 `  ✓/✗ took`；`AgentEnd` 打 usage 一行。
- 每条新增 Message 追加到 session（MessageEntry，parentId 链式）。
- Ctrl+C：第一次 cancel 当前 run，第二次退出（用 `Signal`/shutdown hook 实现即可）。

## 测试清单（全部必须有）

- `pai-model`：
  - `MessageJsonRoundTripTest`：每种 Message/ContentBlock 组合序列化→反序列化相等。
  - `OpenAiCompletionsCodecTest`：fixtures 目录至少 4 个 `.sse` 文件：纯文本流、带 tool_calls（两个并行调用、arguments 分多 chunk）、带 `reasoning_content`、带 usage 末 chunk + `[DONE]`。断言事件序列和最终 AssistantMessage。
  - `AnthropicMessagesCodecTest`：fixtures 至少 3 个：文本、tool_use（`input_json_delta` 分片）、thinking+signature。
  - `StreamProtocolTest`：对所有 fixtures 断言协议约束（Start 首、Done/Error 尾且唯一、index 单调）。
  - `toRequestBody` 测试：给定含 ToolResult/Thinking(signature) 的历史，断言输出 JSON 结构（golden 文件对比）。
- `pai-core`：
  - `AgentLoopTest`（FakeProviderImpl 按脚本产事件）：无工具一轮结束；一轮两个 READ_ONLY 工具并行且结果顺序正确；一个 WRITE 工具串行；未知工具 → error 回灌；hooks BLOCK → error 回灌且工具未执行；工具抛异常 → isError；`maxTurns` 触发；cancel 中途 → ABORTED 且已产消息返回。
  - `AgentHooksChainTest`：短路/折叠语义。
  - `JsonlSessionStoreTest`：create/append/readAll；损坏行跳过；`buildContext` 含 compaction；`mostRecent`。
  - `ArgsTest`、`JsonSchemaTest`。
- `pai-tools`：四个工具各一个测试类（临时目录）；`edit` 的 0/1/多匹配三种；`bash` 超时。
- `pai-cli`：`ArchitectureTest`（ArchUnit）实现 §2 五条规则；`CliSmokeTest`：FakeProviderImpl + 管道输入 "hi\n/quit\n" → 退出码 0、stdout 含模型文本。

## 验收清单（实施 session 在报告里逐条勾）

- [ ] CI `verify` + `gitleaks` 绿，报告贴链接；本地 `mvn -q verify` 全绿，无 `skipTests`。
- [ ] provider fixtures 来自真实请求（脱敏），文件头有日期/模型注释。
- [ ] ArchUnit 规则（00 §2 五条 + 01 §1.5 命名/可见性）全部有测试且通过。
- [ ] 用真实 Anthropic key：让 agent "读 README 并把第一行改成 X" → 观察到 read → edit 两个工具调用，文件确实改了。
- [ ] 用真实 OpenAI-compat key（DeepSeek 或 GLM）：同样任务成功；若模型回 `reasoning_content`，下一轮请求体里能看到回传（用 `PAI_DEBUG_HTTP=1` 打印请求体验证）。
- [ ] 两个模型各一次：`bash` 跑 `ls` 并总结。
- [ ] 退出后 `--resume`，问"我们刚做了什么"，模型能答出（说明 context 重建正确）。
- [ ] `~/.pai/sessions/.../*.jsonl` 用 `jq` 能逐行解析；每行有 `id/parentId/type`。
- [ ] `pai-core` 主代码 ≤ 2500 行、`pai-model` ≤ 2500 行（`cloc`，不含测试）。超了说明做多了。
- [ ] 全仓 `grep -rn "Legacy\|Adapter\|Compat\b" --include=*.java` 只命中 `OpenAiCompatProviderImpl` 这一个名字（它是 provider 名，允许）。
- [ ] 核心层 `grep -P "[\x{4e00}-\x{9fff}]" pai-model pai-core --include=*.java` 零命中。
- [ ] 报告 `docs/reports/M1-report.md`：做了什么、偏离规格的点（应为空或已说明）、遇到的坑、给 M2 的建议。

## 给实施 session 的交接 prompt

见 `../HANDOFF.md`，用 M1 填模板。
