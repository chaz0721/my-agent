# M3 实施报告

- 分支 / commit：`impl/M3`（D:\PaiCLI\pai-next），基于 main `35ab322`（tag M2）：`682d1e7` core-spi、`7bad796` cli/tui/脚本、`9de5b71` ext-mcp，加上本报告。未合入 main。
- CI：**无**（仍然没有 remote）。本地 Windows `mvn -B -ntp verify` BUILD SUCCESS，共 287 个测试（M2 为 235）：model 112 / core 74 / tools 17 / tui 10 / ext-mcp 37 / cli 37。
- 实施 session：Claude Opus 5.5，2026-10-15。live 用 DeepSeek（deepseek-chat / deepseek-reasoner）+ Node 22 的两个官方 server。

## 1. 验收清单
- [x] **stdio + Streamable HTTP 同时配置，工具出现在 `/mcp tools` 且被模型调用成功**：`<cwd>/.pai/mcp.json` 配了 `fs`（`npx -y @modelcontextprotocol/server-filesystem ${M3_DATA}`）和 `remote`（`npx @modelcontextprotocol/server-everything streamableHttp`，`http://127.0.0.1:3001/mcp`）。`/mcp` 显示两个都是 ready，各 14 个工具。deepseek-chat 依次调用了 `mcp__fs__read_text_file`、`mcp__remote__get-sum`、`mcp__remote__echo`，回答 `MARMALADE 42`（附录 A）。HTTP 另有 JDK `HttpServer` mock 的端到端测试，覆盖 Mcp-Session-Id、SSE 回复、202、DELETE。
- [x] **kill server → `/mcp` 显示 error → `/mcp reload` 恢复**：`Stop-Process` 掉 server-filesystem 的 node 进程后，`/mcp` 显示 `fs stdio error 0 connection lost: process exited with code -1`。执行 `/mcp reload fs` 后恢复为 `ready 14`，模型随后调 `mcp__fs__list_directory` 成功。`/quit` 后残留的 server-filesystem 进程数为 0。
- [x] **jar 放 `%USERPROFILE%\.pai\extensions\`，不在 cli classpath**：`jar tf pai-cli.jar` 中 `ext/mcp` 条目数为 0。`/extensions` 输出 `loaded mcp [C:\Users\62728\.pai\extensions\pai-ext-mcp.jar]`。上面两条 live 都是在这个状态下跑的。
- [x] **`git diff --stat M2..HEAD -- pai-core/src/main`**：见 §3。core 没有 bug fix，只有授权的两个 SPI 方法、它们的直接接线，以及规格指定放在 core 的 `ExtensionLoader.loadFromDirs` / `/extensions`。
- [x] **报告含 SPI 漏洞清单**：见 §5。
- [x] 来自 M2 的输入：
  - `--resume` 恢复模型：`/model …deepseek-reasoner` 后退出，再 `--resume`，启动行显示 `model=openai-compat/deepseek-reasoner`，新回复的 `modelId=deepseek-reasoner`（附录 B）。
  - 启动脚本：`pai.cmd` 已在上面的 live 中使用，JLine 正常。
  - `check-budgets.sh` 已纳入 pai-tui。
  - `ToolCallSummaries` 对 MCP 工具用 `mcp__x__y({...})` 格式。
- [x] grep 结果为 0：TODO/FIXME/XXX、core/model/ext 的 CJK、core/ext 的 System.out/err。session 文件中 key 出现次数为 0，`sk-…` 形态字符串为 0。

## 2. `build/check-budgets.sh`
```
pai-model LOC=2327 (max 4000)
pai-core LOC=3882 (max 5000)
pai-cli LOC=1138 (max 1500)
pai-tui LOC=808 (max 1500)
budgets OK
```

## 3. `git diff --stat M2..HEAD -- pai-core/src/main`（10 个文件，+358/−38）
| 文件 | 改动 | 依据 |
|---|---|---|
| `loop/LoopState.java` +11 | `default Optional<String> leafEntryId()` | 授权 SPI |
| `internal/LoopSnapshot.java` +20 | 第 8 个分量 `leafEntryId`；保留 7 参构造器 | 直接接线 |
| `loop/AgentLoop.java` +34 | `withLeaf(Supplier<Optional<String>>)`，取快照时询问；私有 `Parts` record（构造参数 ≤5） | 直接接线：loop 不知道 session，leaf 只能由宿主供给 |
| `compaction/CompactingHooks.java` +21 | 改用 `state.leafEntryId()`；`Target` 只剩 `session` | 直接接线（M2 的裁决） |
| `ext/ExtensionContext.java` +7 | `Path cwd()` | 授权 SPI |
| `ext/ExtensionHost.java` +46 | 实现 `cwd()`；记录 `ExtensionStatus`；拒绝同 id；jar 扩展的 `LinkageError` 也禁用 | 规格"扩展加载"节 + `/extensions` |
| `ext/ExtensionLoader.java` +160 | `loadFromDirs`：每个 jar 一个 URLClassLoader；只取 jar 自身的 provider；读 descriptor；校验 `requiresCoreApi`；新增 `install(…, cwd)` | 规格签名 |
| `ext/ExtensionStatus.java` +51 | `/extensions` 用的 record | 规格"列出已加载/禁用及原因" |
| `skill/SkillsExtension.java` +45、`META-INF/services` +1 | 无参构造器；`defaultRoots(ctx)` | 授权（走 ServiceLoader） |

bug fix：**无**。ADR 只追加了 0005 与 0003 的修订记录。

## 4. 签名异议 / 新增公开成员（需主控登记）
1. `CompactingHooks.Target` 从 `(session, leafId)` 改为 `(session)`。这是 M2 登记过的签名，leaf 改为来自 `LoopState`。
2. 新增公开成员：
   - `AgentLoop.withLeaf`
   - `ExtensionLoader.install(List, Path, Path)`、`CORE_API_VERSION`、`DESCRIPTOR`
   - `ExtensionHost.statuses()`
   - `ExtensionStatus`
   - `SkillsExtension()`、`defaultRoots`
3. `McpTransport` 在规格的 `send/onReceive` 之外加了 `onClosed(Consumer<String>)`，并把 `close()` 声明为不抛受检异常。没有它就无法实现"pending 以异常终态结束"和"kill → error"。`McpClient` 相应加了 `onClosed/isClosed`。
4. `McpServerManager` 的方法是 `configure/startAll/reload/stopAll/servers`，规格写的是 start/stop/reload。

## 5. SPI 漏洞
1. **拿不到 pai home**：`ExtensionContext` 只有 `configDir()`（`~/.pai/ext/<id>/`）。`McpExtension` 和 `SkillsExtension` 用 `configDir().getParent().getParent()` 推出 `~/.pai`，依赖的是 Javadoc 写明的目录布局。绕过方式即如此。建议把这个布局写成合同，或者加 `paiHome()`。
2. **动态注册的线程安全没有约定**：`ExtensionHost.Context` 用 `ArrayList` 记录 `toolNames`，后台线程并发 `registerTool` 会有竞态（`unregisterTool` 也不更新它）。绕过方式：MCP 的全部注册/注销都在 `McpServerManager` 锁内串行执行。ADR 0005 修订记录已写"调用方自行串行化"。建议 M4 让 core 的 Context 本身线程安全，算 bug fix。
3. **扩展没有给用户的通知通道**：server 断连只能写 logger（stderr）；只有用户执行 `/mcp` 时才看得到。不阻塞。

## 6. 偏离与理由
- 资源工具按 server 注册为 `mcp__<server>__read_resource`，只在 server 声明了 resources 能力时注册，不做全局的 `read_resource`：生命周期跟着 server 走，并且符合 `mcp__` 前缀。
- 工具名只保留 `[A-Za-z0-9_-]`，截断到 64 字符（provider 的限制）。schema 原样传递（有测试 `isSameAs`）。
- HTTP 不打开可选的 GET SSE 流，所以经 HTTP 的 `list_changed` 只能夹在 POST 响应里到达。stdio 的 `list_changed` 已测。
- Windows 上没有扩展名的命令（如 `npx`）经 `cmd.exe /c` 启动；关闭时先杀子孙进程。
- pai-cli 以 **test scope** 依赖 pai-ext-mcp（为了让 ArchUnit 能看到），运行时 jar 不含它。
- live 中 `${M3_DATA}` 来自进程环境变量：远程工具禁止写 `.env` 文件。`<cwd>/.env`、`~/.env` 两层由 `McpConfigTest` 覆盖。

## 7. 坑
- Windows 上如果用 `…\bin\java`（不带 `.exe`）启动，会被包一层 `cmd /c`。这时只杀 wrapper，孙进程仍持有管道，读线程永远等不到 EOF。见 `StdioTransportImpl.destroyTree`，以及测试里的 `javaExecutable()`。
- `URLClassLoader` 在 Windows 上会锁住 jar，测试必须先 close，`@TempDir` 才能删掉（`ExtensionLoaderTest.closeLoaders`）。
- 子 loader 上的 `ServiceLoader` 也会返回父 loader 的 provider，需要按 `p.type().getClassLoader() == cl` 过滤（`ExtensionLoader.instantiate`）。
- Surefire 用 manifest-only classpath。测试里调用 javac 或启动子 JVM 时，要显式追加 code source（`ExtensionLoaderTest.classPath`、`StdioTransportImplTest.fakeServerCommand`）。
- PowerShell 用 `-Command "…$x…"` 时，`$x` 会被外层 shell 先展开，应改用 `.ps1`（`target/live/run-pai.ps1`）。
- server-everything 的 `get-env` 工具会返回该 server 进程的全部环境变量（可能含 key）。本次未调用。

## 8. 建议（未做）
- ExtensionContext 补 `paiHome()`，Context 线程安全（§5）。
- HTTP 的 GET 流与 `Last-Event-ID` 续传；MCP prompts、resource templates、sampling。
- `check-budgets.sh` 给 `pai-ext-*` 定预算。ext-mcp 当前主代码 1476 行，最大文件 `McpClient` 313 行。
- `/mcp` 增加 `disable <name>`；server 断连时给用户一行提示（需要 §5.3 的通知通道）。

## 9. 给 M4 的输入
- MCP 工具的 effect 一律是 `UNKNOWN`，ext-hitl 应默认要求审批（参见 §7 的 `get-env`）。
- `<cwd>/.pai/mcp.json` 和 `<cwd>/.pai/extensions/*.jar` 都会执行仓库自带的代码。审查修复 M-1 后，启动时 renderer 会醒目提示：`! loaded extensions from ./.pai/extensions (<ids>) - only run pai in directories you trust`、`! MCP servers from ./.pai/mcp.json are started - only run pai in directories you trust`（`TrustNotices`，CliSmokeTest 断言出现与不出现）；mcp 扩展另有 WARN 日志。但这只是提示，不是拦截，应并入 M4 的"项目信任确认"（与 DECISIONS-LOG 的 N1 同类）。
- HITL 钩子可以用 `LoopState.leafEntryId()` 把审批记录作为 `CustomEntry` 挂到当前分支上。

## 附录
**P3C**：reporting 模式；maven-pmd-plugin 3.13.0，PMD 6.21.0，p3c-pmd 2.1.1。excludes 共 65 个（新增 2 个 core、13 个 ext-mcp）。8 条 Priority 2 的 `LowerCamelCaseVariableNamingRule` 都是嵌套 record 被误报（如 `Target`、`Io` 等），ext-mcp 为 0 条。

**行数**（去空行与注释，主 / 测试；设备无 cloc，用同口径脚本统计）：model 1659 / 977，core 2485 / 1804，tools 428 / 286，tui 612 / 232，ext-mcp 1476 / 1075，cli 974 / 626。

**A. MCP live**（`pai.cmd`，Plain，jar 在 `~/.pai/extensions`）
```
[main] WARN pai.ext.mcp - starting MCP servers from ...\m3\.pai\mcp.json; only run pai in directories you trust
INFO McpServerManager - mcp server remote ready with 14 tools / mcp server fs ready with 14 tools
> /extensions   loaded skills [classpath] / loaded builtin-tools [classpath] / loaded mcp [C:\Users\62728\.pai\extensions\pai-ext-mcp.jar]
> /mcp          fs stdio ready 14 / remote http ready 14
> mcp__fs__read_text_file({"path":"D:\\...\\data\\no...) ok / mcp__remote__get-sum({"a":17,"b":25}) ok / mcp__remote__echo({"message":"MARMALADE"}) ok
MARMALADE 42
(kill node) > /mcp   fs stdio error 0  connection lost: process exited with code -1
> /mcp reload fs     fs stdio ready 14
> mcp__fs__list_directory(...) ok -> "The directory contains two files: notes.txt and todo.md."
```
**B. --resume**：`ff56ac18 assistant modelId=deepseek-chat` → `8757ba7d model_change openai-compat/deepseek-reasoner` → `--resume` 后启动行为 `model=openai-compat/deepseek-reasoner … resumed=2 messages`，接着 `2f3df68b assistant modelId=deepseek-reasoner`。

## 10. 审查修复（主控裁决 2026-10-15，均已补测试）
| # | 修复 | commit | 测试 |
|---|---|---|---|
| M-1 | `TrustNotices`：`./.pai/extensions` 下已加载的 jar 与 `./.pai/mcp.json` 走 renderer 醒目提示 | ae03ced | `CliSmokeTest.project_extension_jars_get_a_notice` / `project_mcp_json_gets_a_notice` / `no_trust_notices_for_a_plain_directory` |
| S-1 | `onLoad` 只启动不等待；首次 `prepareRequest` 调用 `awaitReady(8s)`，之后不再等。由于 loop 在 hook 之前就算好了 tools，hook 会把等待期间注册的 MCP 工具补进本次请求 | 56ae208 | `McpExtensionStartupTest`、`McpServerManagerTest.await_ready_*`；live 首屏无等待，首条请求即调用 fs+remote 工具 |
| S-2 | close 前快照 `descendants()`，结束后对其中仍存活的进程执行 destroyForcibly | 56ae208 | `StdioTransportImplTest.close_also_ends_processes_the_server_started`（slow，fake server 起一个睡眠孙进程） |
| S-3 | 无 id 的消息（notification/response）在调用线程同步 POST | 56ae208 | HTTP 测试断言 initialized 是第 2 个到达 |
| S-4 | `InitializeResult.hasTools()`；无该能力时跳过 `tools/list` | 56ae208 | `server_without_tools_capability_only_gets_the_resource_tool` |
| S-5 | `connect` 打开后在锁内记录 `connecting`，`detach` 一并返回由调用方 close；`stopAll` 之后 `awaitTermination(3s)`；Ctrl+C 退出前执行 `host.unloadAll()` | 56ae208 / ae03ced | `stop_all_closes_a_client_that_is_still_starting` |
| S-6 | 名称被改写时末尾加 `_` + 6 位 SHA-256；碰撞的工具记入 skipped，`/mcp tools` 列出 | 56ae208 | `McpToolTest.changed_names_*`、`colliding_tool_names_are_listed_as_skipped` |
| S-7 | `McpConfig.Source` 按文件作用域取变量：home 文件只看 env + `~/.env`，项目文件看 env + `<cwd>/.env` + `~/.env`；`cmd.exe /c` 包装时，参数含 `& \| ^ % < > "` 则拒绝启动 | 56ae208 | `cwd_env_is_only_visible_to_the_project_file`、`cmd_wrapping_refuses_metacharacters` |
| S-8 | ADR 0005 修订记录改为"M3 过渡：…M4 起 Context 线程安全" | 本 commit | — |
| S-9 | 已取消的 token：不 send，也不发 cancelled 通知 | 56ae208 | `an_already_cancelled_token_never_sends_the_request` |
| R-2 | HTTP `Redirect.NEVER` | 56ae208 | — |
| R-5 | 先注册命令和 hook，再 configure/startAll；异常时 stopAll 后重新抛出 | 56ae208 | `failing_start_after_registration_stops_what_was_started` |

修复后共 301 个测试（ext-mcp 48，cli 40）。新增 SPI 观察：hook 无法改变 loop 已算好的工具集合，S-1 只能在 `prepareRequest` 里补 definition。记录：协议版本校验、404 重新 initialize、resources/list_changed、readLine 行长限制、子进程继承环境变量、descriptor id 与 `Extension.id` 不一致，均未做。

