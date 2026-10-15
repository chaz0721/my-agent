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

## 修订记录

- 2026-10-15（M3）：`ExtensionContext` 新增 `Path cwd()`（pai 启动时的工作目录，供扩展读 `<cwd>/.pai/` 下的项目级配置）。这是 M2 裁决的 SPI 变更：没有它，`SkillsExtension` 只能由 cli 构造后传入，无法走 ServiceLoader；M3 起 `SkillsExtension` 有无参构造器并在 pai-core 的 `META-INF/services` 中声明。同期落地本 ADR 预告的目录加载：`ExtensionLoader.loadFromDirs(List<Path>)` 扫描 `~/.pai/extensions/*.jar` 与 `<cwd>/.pai/extensions/*.jar`，每个 jar 一个 `URLClassLoader`（parent 为 pai-core 的 loader），jar 必须带 `META-INF/pai-extension.properties`（`id`、`requiresCoreApi=1`，即 `ExtensionLoader.CORE_API_VERSION`），版本不符、不可读、`onLoad` 抛异常或与已加载扩展同 id 的一律禁用并记入 `ExtensionHost.statuses()`（`ExtensionStatus` record），不拖垮主程序。`ExtensionContext` 的其余方法不变；`onLoad` 之后在后台线程调用 `registerTool`/`unregisterTool` 是允许的。M3 过渡：动态注册由调用方串行化；M4 起 `ExtensionHost.Context` 线程安全（见 DECISIONS-LOG）。
- 2026-10-15（M4）：`ExtensionContext` 新增四个方法（M3 SPI 漏洞 1/3 与项目信任的裁决）：`Path paiHome()`（`~/.pai` 或 `PAI_HOME`；目录布局是合同：`sessions/`、`extensions/`、`ext/<id>/`、`skills/`、`models.json`、`mcp.json`、`.env`，M4 起另有 `trust.json`）；`void notify(String)`（一行用户提示，任意线程可调，tui 渲染，Plain 与普通输出同流）；`Optional<UserInteraction> ui()`（新 SPI 接口 `UserInteraction { String ask(String prompt, List<String> choices); boolean confirm(String prompt); }`，仅交互运行时存在；为空时扩展必须取安全默认值，ext-hitl 为拒绝）；`boolean projectTrusted()`（cli 在 `ExtensionLoader.install` 之前完成信任判定并传入；未信任时扩展不得读取 `<cwd>/.env`、`<cwd>/.pai/` 下会执行代码或改写凭据去向的配置）。宿主侧以 `ExtensionHost.Settings(paiHome, cwd, projectTrusted, ui, notifier)` 传入（`ExtensionLoader.install(List, Settings)`；旧的 `install(List, Path[, Path])` 视为受信任、无 ui）。`ExtensionHost.Context` 自 M4 起线程安全（`unregisterTool` 同步更新其登记表）。**hook 顺序**：classpath 扩展（ServiceLoader 发现顺序）先于目录 jar 扩展（目录顺序 `~/.pai/extensions` 再 `<cwd>/.pai/extensions`，目录内按文件名），同一扩展内按 `addHooks` 调用顺序；`beforeToolCall` 短路、其余顺序折叠。`AgentLoop` 在 `prepareRequest` 之后把调用期间新注册的工具（registry 现在 − 调用前）并入钩子返回的工具列表，钩子有意删掉的工具不会被加回。
- 2026-10-15（M5，主控）：`ExtensionContext` 新增 `Optional<String> option(String key)`——cli 的启动参数与环境变量以 key-value 经 `ExtensionHost.Settings.options`（新增分量，保留旧构造器）传给扩展；键名小写短横线（如 `approval`），值为原始字符串，扩展自行解析。用途：`--approval` 等运行期开关，取代 M4 临时使用的 System property（全局状态，测试易污染）。扩展**不得**用它读取 API key 等凭据——凭据仍只经 provider 配置路径。
- 2026-10-15（M5，主控）：`ExtensionContext` 新增 `void appendEntry(String customType, JsonNode data)` 与 `Optional<SessionEntry.CustomEntry> latestEntry(String customType)`，落实 ADR 0004 "扩展可写 `CustomEntry`"的预告——此前扩展没有任何途径写入 session，任务清单/审批记录/子代理记录都做不出来。语义：`appendEntry` 以宿主当前 leaf 为 parent 追加并推进 leaf（宿主经 `ExtensionHost.Settings` 注入 session 访问回调；无 session 时忽略）；`latestEntry` 沿当前分支的 parent 链查找，`/fork` 后按分支隔离。`buildContext` 继续忽略 `CustomEntry`；扩展只能读写自己 customType 的数据，不得借此改写消息历史。
- 2026-10-15（M5，主控）：`ExtensionContext` 新增 `Optional<Tool> tool(String name)` 只读查找，供扩展复用已注册工具的执行路径（首个用例：ext-verify 经内置 `bash` 跑验证命令，获得同一套 shell 选择/超时/进程树清理/输出截断）。约束：由此拿到的工具调用**不经过** hooks 链（无审批、无 Guard），因此只允许用于执行**非模型生成**的命令（内置默认规则或受信任项目的配置文件），且必须用 `notify` 让用户事先看到将运行什么。扩展之间仍不得互相依赖；跨扩展共享 Guard 若成为需求，走"Guard 下沉到 core `policy` 包"的 ADR，不走 ext 间引用。
- 2026-10-16（M7.5）：**子代理 SPI**。`ExtensionContext` 新增 `AgentLoop subLoop(AgentConfig config, Predicate<Tool> offered)`：宿主以其**当前 provider**、注册表中通过 `offered` 过滤后复制出的新 `ToolRegistry`、以及钩子链中**仅** `beforeToolCall`/`afterToolCall` 的转发（审批/Guard/验证钩子照常生效，且走父会话的 UI），构造一个独立的 `AgentLoop`；`prepareRequest`/`transformContext`/`steeringMessages` **不转发**——skill 索引、记忆注入、任务清单、恢复消息都挂在这些钩子上，子代理（尤其 explore/reviewer）必须从空上下文开始；需要的提示词由扩展自己放进 `AgentConfig` 的 system prompt。`AgentConfig.stages()` 由扩展决定（默认两段，子代理有自己的压缩）。宿主侧：`ExtensionHost.Settings` 新增 `Supplier<Optional<Provider>>` 分量（保留旧构造器 + `withProvider`），cli 以 Repl 当前 provider 供给；扩展**不得**自建 provider（凭据边界，M5 修订不变）。禁止嵌套由扩展自己保证（过滤掉 `spawn_agent/agent_result/agent_cancel`）。拒绝的替代方案：cli 以 compile scope 依赖 ext 并手工构造扩展（违反"cli 不 import ext"与"不递出内部对象"）。
