# M3 规格书：扩展 SPI 定型 + MCP 作为第一个外部扩展

> 前置：M2 通过。本阶段是对 ADR 0005 的压力测试：**如果 MCP 需要改 core 才能接上，说明 SPI 设计有漏洞，先修 SPI（并更新 ADR），再做 MCP。**

## 目标

1. 扩展可以从 `~/.pai/extensions/*.jar` 和 `<cwd>/.pai/extensions/*.jar` 加载（classpath 隔离到每个扩展一个 `URLClassLoader`）。
2. `pai-ext-mcp` 模块：读 `~/.pai/mcp.json` + `<cwd>/.pai/mcp.json`，启动 stdio / Streamable HTTP server，把 server 工具作为 `Tool` 注册（`mcp__<server>__<tool>`），支持 `tools/list_changed` 热刷新、resources 作为 `read_resource` 工具、`/mcp` 命令查状态/重载。
3. **core 零改动**（允许 bug fix；不允许加 SPI 方法。若必须加 → 先提 ADR 修订）。

## 来自 M2 的输入（已裁决，纳入本阶段）
- SPI 变更（ADR 0005 追加修订记录）：`ExtensionContext.cwd()` 返回 `Path`；`LoopState.leafEntryId()` 返回 `Optional<String>`。随后 `SkillsExtension` 改为可走 ServiceLoader，`CompactingHooks` 用 `leafEntryId()` 定位 firstKept。
- 启动脚本 `pai.cmd`（Windows）与 `pai`（sh）放仓库根目录，内置 `--enable-native-access=ALL-UNNAMED`；`check-budgets.sh` 把 pai-tui 纳入（上限 1500）。
- `--resume` 时从分支上最近的 `ModelChangeEntry` 恢复模型。
- MCP 工具在 `ToolCallSummaries` 用通用格式 `mcp__server__tool(args 摘要)`。

## 扩展加载

```java
package dev.pai.core.ext;
public final class ExtensionLoader {
  public List<Extension> loadFromServiceLoader();                        // M1 已有
  public List<Extension> loadFromDirs(List<Path> dirs);                  // 每个 jar 一个 URLClassLoader（parent = pai-core 的 loader），ServiceLoader 在该 loader 上查
}
```
- jar 内 `META-INF/pai-extension.properties`：`id=...`, `requiresCoreApi=1`（简单整数版本，不匹配拒绝加载并告警）。
- 扩展异常不得拖垮主程序：`onLoad` 抛异常 → 该扩展禁用 + 一行 warn；工具执行异常已由 loop 兜住。
- `/extensions` 命令列出已加载/禁用及原因。

## pai-ext-mcp

目录：`pai-ext-mcp/`，包 `dev.pai.ext.mcp`，**只依赖 pai-core、pai-model、Jackson、JDK HttpClient**。不引官方 MCP SDK（它拉 Spring/Reactor 一堆东西）。参考 pi 的 `packages/mcp`（独立 client，协议/transport 分层）和旧仓库 `mcp/` 的经验。

```java
public final class McpExtension implements Extension { id()="mcp"; }

// 协议层（无 I/O）
public final class JsonRpc { /* request/response/notification 的 record + 编解码 + id 生成 */ }
public interface McpTransport extends AutoCloseable { void send(JsonNode msg) throws IOException; void onReceive(Consumer<JsonNode> c); }
public final class StdioTransportImpl implements McpTransport {}         // ProcessBuilder，按行 JSON；stderr 转 logger
public final class StreamableHttpTransportImpl implements McpTransport {} // POST + 可选 SSE 响应流；Mcp-Session-Id header

// 客户端
public final class McpClient implements AutoCloseable {
  public McpClient(McpTransport t, Duration requestTimeout);
  public InitializeResult initialize(ClientInfo info);
  public List<McpToolDescriptor> listTools();                          // 跟 cursor 分页；上限 100 页/10000 项
  public CallToolResult callTool(String name, JsonNode args, CancelToken c);
  public List<McpResource> listResources(); public ReadResourceResult readResource(String uri);
  public void onNotification(String method, Consumer<JsonNode> handler);   // tools/list_changed, resources/list_changed
}

// 桥接到 core
final class McpTool implements Tool { effect()=UNKNOWN; concurrencySafe()=false; definition() 用 server inputSchema 原样（不做有损清洗） }
final class McpServerManager { start/stop/reload；每 server 一个 lifecycleVersion，晚到的结果按版本丢弃；工具注册/注销走 ExtensionContext }
```

- 工具热刷新用 M1 已有的 `ExtensionContext.unregisterTool/registerTool`；若发现还需要别的 SPI 能力 → 按本文件开头流程处理。
- 配置 `${VAR}` 展开：环境变量、`<cwd>/.env`、`~/.env`。
- 启动不阻塞首屏：server 并发初始化，8 秒内没好的标 `STARTING` 后台继续；`/mcp` 看状态。
- 关闭：`onUnload` 时 pending 请求全部以异常终态结束，不接受新请求。
- 错误传递：`CallToolResult.isError` → `ToolResult.isError`；不要用字符串前缀判断错误。

## `/mcp` 命令

`/mcp` 列表（name、transport、status、tools 数）；`/mcp reload [name]`；`/mcp tools <name>`。

## 测试清单

- `JsonRpcTest`、`StdioTransportTest`（用一个测试用的 Java 子进程 echo server，`@Tag("slow")`）、`StreamableHttpTransportTest`（JDK `HttpServer` mock）。
- `McpClientTest`：in-memory transport；分页；`list_changed` 触发回调；超时；close 后 pending 异常。
- `McpServerManagerTest`：版本号丢弃晚到结果；reload 后旧工具注销新工具注册。
- `ExtensionLoaderTest`：从临时目录加载一个测试 jar；坏 jar 被禁用不崩；版本不匹配拒绝。
- ArchUnit 新增：`dev.pai.ext..` 不得依赖 `dev.pai.cli..`/`dev.pai.tui..`。

## 验收清单

- [ ] `@modelcontextprotocol/server-filesystem`（stdio）和任一 Streamable HTTP server 同时配置，工具都出现在 `/mcp tools` 并可被模型调用成功。
- [ ] kill 掉 server 进程，`/mcp` 显示 error，`/mcp reload` 恢复。
- [ ] 把 pai-ext-mcp 打成 jar 放 `~/.pai/extensions/`，**不在 cli classpath 里**，启动后仍可用（证明外部加载链路）。
- [ ] `git diff --stat M2-tag..HEAD -- pai-core/src/main` 只有 bug fix（报告里逐条列出并说明为什么是 bug 而不是功能）。
- [ ] 报告 `docs/reports/M3-report.md`，含"SPI 漏洞清单"（哪怕为空也要写明）。
