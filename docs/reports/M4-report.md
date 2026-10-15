# M4 实施报告

- 分支 / commit：`impl/M4`。4A `6260e46`、4B `2ec3d34`、4C `120488a`、报告 `本 commit`。未 push，未合入 main。
- CI：仓库没有 remote，CI 没跑。本地 Windows `mvn -B -ntp verify` 绿，共 443 个测试：model 133、core 84、tools 17、tui 13、ext-mcp 52、ext-hitl 60、ext-memory 26、cli 58。
- 实施：Claude Opus 5.5，2026-10-15。接手上一窗口未验证的 4A。

## 1. 验收清单
- [x] **Provider**：只有 DeepSeek 有 key。`/model deepseek/deepseek-chat` 对话通过（回复 `PINEAPPLE`），经该预设的工具调用也通过（4C 的 `remember`）。其余 6 个跑 golden：`PresetRequestBodyTest` 14 例；golden 已人工检查，xfyun 不带 `tools`，deepseek/kimi 回传 `reasoning_content`（按 provider+model 判断）。重试由 `RetryingProviderImplTest` 7 例覆盖（429→200、已有 partial 不重试、Retry-After）。live 里 DeepSeek 多次返回 `HTTP 503 Service is too busy`，`retry 1/3`、`2/3` 之后都成功了。
- [x] **CommandGuard**：DeepSeek 两次拒绝执行 `rm -rf /` 和 `rm -rf ..`，所以"模型主动发起递归删除"复现不了。改用它自己提议的 `echo rm -rf /`，结果 `x $ echo rm -rf / (tool call blocked: policy: command matches guard rule R1 (blocked by pai, cannot be approved)`：没弹审批，也没执行。表驱动测试 19 拦 / 13 放；另有"所有 mode 下守卫都先于审批"的测试。
- [x] **PathGuard**：写 `D:\PaiCLI\tmp\m4live\outside.txt` 被拦，`policy: path outside the working directory`，文件不存在。
- [x] **edit 审批**：弹出 unified diff；答 `a` 后第二次 edit 不再问，`/approvals` 显示 `always allowed this session: edit`。在真实 Windows 控制台（JLine）用 `WriteConsoleInput` 注入按键又跑了一遍：read 答 `a`，edit 显示 diff 后答 `y`，文件最终为 `hello pal`（附录 B）。
- [x] **项目信任**：新目录 `work2/.env` 写入 `PAI_ANTHROPIC_API_KEY=<假值>`，启动时询问是否信任。答 `n` 后 provider 仍是 `openai-compat/deepseek-chat`，并提示 `! project not trusted …`。执行 `/trust` 后，`trust.json` 记为 `{"D:\\PaiCLI\\tmp\\m4live\\work2": true}`；重启后 `model=anthropic/claude-sonnet-4-5`，并提示 `! trusted project: loaded ./.env`。
- [x] **MCP 工具（UNKNOWN）**：ASK 模式下 `mcp__fake__echo` 弹审批，显示完整参数；答 `n` 得到 `user denied`。
- [x] **/memory**：list、search、delete、clear 四个子命令和 `inject on|off` 都可用；clear 会先让用户确认。`remember` 一条偏好后开新 session，在 `PAI_DEBUG_HTTP=1` 下 system prompt 含 `<memory>…- [6000437d] Wants code comments written in British English.…</memory>`。写入 secret 形态的文本被拒：`refused: … API key`，记忆文件里没有它。记忆文件只追加（删除写一条带 `deletedAt` 的行）。用 key 扫描 m4live 下所有文件和 debug 日志：0 命中。
- [x] **core 改动**仅限授权清单（§3）；ADR 0005 已加修订记录。
- [x] **其他**：cli 的 `/memory` 已改名 `/context`；ext 预算 2500；讯飞通过 capabilities 去掉 TOOLS，不发 tools；`McpExtension` 删掉了补工具逻辑；TODO、核心与 ext 主代码的 CJK、System.out/err 都是 0。

## 2. `build/check-budgets.sh`
```
pai-model LOC=2663 (max 4000)
pai-core LOC=4048 (max 5000)
pai-cli LOC=1488 (max 1500)
pai-tui LOC=863 (max 1500)
pai-ext-hitl LOC=695 (max 2500)
pai-ext-mcp LOC=2166 (max 2500)
pai-ext-memory LOC=637 (max 2500)
budgets OK
```

## 3. `git diff --stat M3..HEAD -- pai-core/src/main`（6 个文件，+208/−23，全在授权范围内）
- `ext/ExtensionContext`（+34）：`paiHome()`、`notify()`、`ui()`、`projectTrusted()` 四个方法。
- `ext/UserInteraction`（+29）：SPI 接口，签名按裁决。
- `ext/ExtensionHost`（+85/−）：Context 线程安全（CopyOnWrite），`unregisterTool` 同步更新 toolNames；新增 `Settings` record 承载上面四个值（见 §4）。
- `ext/ExtensionLoader`（+17）：`install(List, Settings)`；旧重载视为受信任、无 ui。
- `loop/AgentLoop`（+26）：`prepareRequest` 之后把调用期间新注册的工具并入请求（hook 返回 ∪ (现在 − 调用前)）。
- `prompt/ProjectContext`（+40）：`load(paiHome, cwd, trusted)`；未信任时，项目文件里的绝对路径、带盘符或含 `..` 的 @import 不展开，`~/.pai/PAI.md` 不受此限制。
- docs 改动：`adr/0005` 修订记录（我写的）；`DECISIONS-LOG`、`phases/M4` 是主控写的，随 4A 工作区一起提交。

## 4. 签名异议
1. 规格里 `Approver.ask` 返回 `Decision`。按 Enum 后缀规则实现为 `ApprovalDecisionEnum`，取值 ALLOW_ONCE、ALLOW_ALWAYS、DENY、NO_ANSWER；NO_ANSWER 表示没人能答，拒绝时给出单独的说明。
2. 新增公开成员 `ExtensionHost.Settings(paiHome, cwd, projectTrusted, ui, notifier)` 和 `ExtensionLoader.install(List, Settings)`：四个值要从 cli 传进 host，而参数上限是 5。请主控登记。

## 5. SPI 漏洞
1. 扩展拿不到启动参数。YOLO 只能用 `-Dpai.approval`、`PAI_APPROVAL` 或 `~/.pai/ext/hitl/config.json` 开启；cli 预算只剩 12 行，没加 `--approval`。
2. `beforeToolCall` 没有"策略先于交互"的阶段。memory 的 secret 拒绝只能放在工具内部，于是会先弹审批、再被拒。
3. 没有运行时重载 provider 和扩展的通道，所以 `/trust` 要重启才生效。

## 6. 偏离
1. 注入：recall 命中不足 5 条时，用最近的记忆补足（≤5 条、≤1500 字）。否则只要首句和偏好没有共同的词，"新 session 的 system prompt 含它"就不成立。
2. `remember` 多了一个可选参数 `scope`（`global` 默认，或 `project`）。
3. 为满足 cli 预算（加信任后达到 1551），删掉了 M1 N1 的精确判断 `ProviderSetup.configuredFromCwdEnv`，改为：受信任且存在 `./.env` 时提示一行 `trusted project: loaded ./.env`。信任确认才是根治。
4. hitl 和 memory 沿用 mcp 的做法：作为 drop-in jar 安装，cli 只在 test scope 依赖它们。默认安装不带审批，见 §8。

## 7. 坑
- `PresetRequestBodyTest` 首次生成的 golden 是平台换行（Windows 上为 CRLF），已转成 LF 入库。比较的是 JSON 树，换行不影响结果。
- PowerShell 里 `mvn … | Select-Object -First N` 会提前结束管道，连 mvn 一起杀掉；应先写日志再过滤。`mvn -rf :x` 要求上游模块已 install。
- CommandGuard 按文本匹配，所以 `echo rm -rf /` 也会被 R1 拦（宁可误拦）。`"rm -rf /"` 前面是引号，不会命中。
- PMD 6 解析不了的文件要加进 excludes：本阶段新增 11 个，共 76 个。
- Desktop Commander 的进程走管道，只能测 Plain。真实控制台要 `AttachConsole` + `CONIN$`/`CONOUT$`；脚本 `D:\PaiCLI\tmp\m4live\console.ps1` 不在仓库里。
- 旧讯飞客户端的 `lora_id` 是可选项，预设里 `extraHeaders` 留空，需要时在 `~/.pai/providers.json` 里配置。

## 8. 建议（均未做）
- 默认安装带上 ext-hitl（classpath）；cli 加 `--approval`（需要放宽预算）。
- ASK 模式下 read 也要审批，live 里很吵。可以考虑默认 AUTO_READ。
- CommandGuard 规则锚定到命令位置；`/trust` 后立即重载；把审批记录写进 session 的 `CustomEntry`。

## 9. 给 M5 的输入
- 只读模式可以直接在 `prepareRequest` 里删工具，loop 不会把删掉的工具加回来（`ToolsAfterHooksTest.a_tool_removed_by_a_hook_stays_removed`）。
- `ExtensionContext.ui()` 可用于计划确认；没有 ui 时必须取安全默认值。hook 顺序是 classpath 扩展先于 jar（ADR 0005 修订记录）。

## 附录
**P3C**：reporting 模式；maven-pmd-plugin 3.13.0、PMD 6.21.0、p3c-pmd 2.1.1。excludes 76 个，本阶段 +11：model 3、cli 2、hitl 3、memory 3。Priority 2 共 12 条，全是嵌套 record 被 `LowerCamelCaseVariableNamingRule` 误报，其中新增 4 条：`CommandGuard.Rule`、`LineDiff.Op`、`Recall.Hit`、`SecretFilter.Rule`。

**行数**（不计空行和注释，主代码 / 测试；同口径脚本，设备上没有 cloc）：model 1892 / 1151，core 2564 / 2034，tools 428 / 286，tui 655 / 264，ext-mcp 1641 / 1503，ext-hitl 549 / 368，ext-memory 562 / 245，cli 1292 / 1079。

**A. Plain live（DeepSeek，`PAI_HOME=D:\PaiCLI\tmp\m4live\home`）**
```
> write D:\PaiCLI\tmp\m4live\outside.txt
x write … (tool call blocked: policy: path outside the working directory: D:\PaiCLI\tmp\m4live\outside.txt (blocked by pai, cannot be approved)
tool edit (write_fs)
--- D:\PaiCLI\tmp\m4live\work\hello.txt
@@ -1,1 +1,1 @@
…   allow? y = yes, n = no, a = always for edit in this session   [y/n/a] a
> edit hello.txt  ok … > edit hello.txt  ok      (第二次没有问)
tool mcp__fake__echo (unknown) { "text" : "MARMALADE" } … n → (tool call blocked: user denied)
```
**B. 真实控制台（JLine，`console.ps1`）**：`tool read (read_only) … [y/n/a] a` → `tool edit (write_fs)`，显示 diff `+hello pal` / `-hello friend`（这次运行在顺序修复之前，修复后先 `-` 后 `+`，见 `CallPreviewTest`）→ `[y/n/a] y` → `ok edit hello.txt` → `/approvals` 显示 `always allowed this session: read`。
**C. 记忆注入**：`has <memory>: True`，`log contains key: False`。
