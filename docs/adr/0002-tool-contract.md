# ADR 0002：工具契约——定义、效果元数据、执行放在同一个对象

状态：已接受　日期：2026-10-14

## 背景

旧仓库 `ToolRegistry`（2004 行）的工具是三处按名字的字符串分发：
- `tools.put(name, new Tool(name, desc, createParameters(...), Map<String,String> → String))`：参数被扁平化为字符串，嵌套/数组参数不可能。
- `descriptorForBuiltin(name)` switch：副作用/并发/审批元数据。
- `ResultPolicy` 第三个 switch。
加一个工具改三处；MCP 工具塞进同一个 map 但执行器是个抛异常的占位。另有 7 个执行入口、ThreadLocal 审批绕过、反射检测子类。

## 决定

```java
public interface Tool {
  ToolDefinition definition();          // name, description, JSON Schema（完整 JSON Schema，允许嵌套/数组/enum）
  ToolEffectEnum effect();                  // READ_ONLY | WRITE_FS | EXECUTE | NETWORK | UNKNOWN
  default boolean concurrencySafe() { return effect() == ToolEffectEnum.READ_ONLY; }
  ToolResult execute(JsonNode arguments, ToolContext ctx) throws Exception;
}

public record ToolResult(List<ContentBlock> content, boolean isError, Optional<JsonNode> details) {
  static ToolResult text(String s); static ToolResult error(String s);
}

public interface ToolContext {
  Path cwd();
  CancelToken cancel();
  void progress(String partial);        // 流式进度，可忽略
}
```

- `arguments` 是 Codec 已解析的 JSON 对象。工具自己用 `JsonNode` 取值；允许用一个小工具类 `Args.of(node).string("path").intOr("limit", 100)` 做校验，不引入注解框架。
- Schema 由工具自己提供（手写 JSON 或用 `JsonSchema.object().string("path", "...").required("path").build()` 小 builder）。Loop 在执行前按 schema 做最小校验（required 字段存在、类型匹配）。
- `ToolRegistry` 只是 `Map<String, Tool>` + 来源标签（哪个扩展注册的）。没有执行逻辑。
- **审批/策略不在 Tool 也不在 Registry**：是 `AgentHooks.beforeToolCall` 的事（ADR 0003/0005），由 ext-hitl 实现。核心只提供 `effect()` 作为决策依据。
- MCP 工具是实现了同一个 `Tool` 接口的普通对象，`effect()` 默认 `UNKNOWN`。

## 后果

- 加工具 = 实现一个接口，在一个地方。
- 并行执行规则从 `effect()`/`concurrencySafe()` 推导，不再有按名字的表。
- 代价：工具实现者要写 JSON Schema。提供 builder 降低成本。
