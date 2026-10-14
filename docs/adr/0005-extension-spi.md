# ADR 0005：扩展 SPI 第一天就有，内置功能也走它；SPI 面是封闭枚举

状态：已接受　日期：2026-10-14

## 背景

旧仓库把 23 期功能全烤进一个 jar 和几个 God class：`Main` 14 种职责、`ToolRegistry` 持有 LSP/JGit/浏览器/web/记忆/skill 的引用、`Agent` 知道渲染/记忆/skill/LSP/压缩/持久化。每加一个功能，这三个类都长。pi 保持小的唯一原因是：核心只提供扩展点，功能全在扩展里。

## 决定

```java
public interface Extension {
  String id();
  default void onLoad(ExtensionContext ctx) {}
  default void onUnload() {}
}

public interface ExtensionContext {
  void registerTool(Tool tool);
  void unregisterTool(String name);                       // 供 MCP 等动态工具源热刷新
  void registerCommand(SlashCommand cmd);                 // "/name args" → handler
  void registerProvider(Provider provider);               // ext 可以带 provider（如企业内网网关）
  void addHooks(AgentHooks hooks);                        // 多个扩展的 hooks 按注册顺序链式调用
  void on(Class<? extends AgentEvent> type, Consumer<AgentEvent> listener);
  void onSession(Class<? extends SessionEvent> type, Consumer<SessionEvent> listener);   // SessionStart/SessionEnd/BeforeCompaction
  Path configDir();                                       // ~/.pai/ext/<id>/
  Logger logger();
}

public interface SlashCommand {
  String name(); String description();
  CommandResult run(String args, CommandContext ctx);     // ctx 可读 session/当前 model/发送 user message
}
```

- 发现机制：`java.util.ServiceLoader<Extension>`（META-INF/services）。M1 只需内置扩展（pai-tools）；M3 加 classpath 目录扫描 `~/.pai/extensions/*.jar`。
- **内置 4 工具是扩展** `dev.pai.tools.BuiltinToolsExtension`。cli 不特殊对待它。
- **SPI 是封闭的**：`ExtensionContext` 方法集和 `AgentHooks` 钩子集是枚举完的；不提供 `getAgentLoop()`、`getRegistry()` 之类递出内部对象的方法。要新钩子 → 改 ADR，不是加 getter。
- hooks 链式语义：`beforeToolCall` 任一 BLOCK 即 BLOCK（短路）；`afterToolCall`/`transformContext` 顺序折叠。
- 扩展间不直接依赖；需要共享状态走 session `CustomEntry` 或各自 configDir。

## 后果

- MCP、HITL、记忆、微信、国产 provider 特殊逻辑全是扩展模块，核心长期保持 <5k 行。
- 内核 API 的兼容承诺范围 = `Extension`/`ExtensionContext`/`AgentHooks`/`Tool`/`SlashCommand` + model 类型。其他全是实现细节。
- 代价：扩展做不到"任意事"。这是 feature。
