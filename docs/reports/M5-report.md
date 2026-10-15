# M5 实施报告

- 分支 / 合入 commit：`impl/M5`（未 push、未合入 main）
- CI 链接：未 push，CI 未跑；本地 `mvn -B verify` 全绿，共 582 个测试（model 133、core 88、tools 17、tui 19、ext-mcp 52、ext-hitl 148、ext-memory 41、ext-tasks 5、ext-verify 11、cli 68）
- 实施 session：Claude Opus 5.5，2026-10-15

## 1. 验收清单（5A/5C/5D）
- [x] **真实模型多文件任务**（DeepSeek，AUTO_READ，`D:\PaiCLI\tmp\m5live\work`）：首轮先 `todo_write`（7 项）→ 逐项执行，期间 todo_write 共 5 次，session 里有 5 条 `custom/tasks`；故意漏掉分号的 edit，其结果后面追加了 `[verify: mvn -q compile] failed (exit 1), output tail: … Calc.java:[9,21] …`，模型据此修好；修复与 Greeter 在同一回复里，只编译一次（debounce），报告挂在后一个 edit 上：`[verify: mvn -q compile] passed`；最后 `mvn -q test` exit 0；`/todo` → `Task list (7/7 completed)`。证据见附录 A。
- [x] **`/plan` 下写入被拦并给出方案**：`echo x > PLAN_PROBE.txt` 被拦（`tool call blocked: plan mode: read-only…`），`ls -R . | head -50` 放行，最后输出 4 步方案。`/plan save` 写入 `.pai/plan.md`，`/plan off` 后恢复 auto-read。请求体（`PAI_DEBUG_HTTP=1`）：PLAN 下 3 个请求都带 `<plan-mode>`，工具只剩 `load_skill,read,bash,todo_write,todo_read`；下一 run 的 2 个请求带 `<plan>`，并重新带上 `write,edit`；再下一 run 不再带 `<plan>`。之后的 run 执行了方案（edit×2，`mvn -q test` 5/5）。见附录 B。
- [x] **Shift+Tab 真实 Windows 控制台**（`console.ps1`，WriteConsoleInput 发 VK_TAB+SHIFT）：连按 4 次依次输出 `mode plan / ask / auto-read / plan`，不会进入 yolo。见附录 C。
- [x] **YOLO 只能显式开启**：`PAI_APPROVAL=yolo` 启动时有 `! approvals: yolo mode …` 提示；`cycle` 四次依次为 ask / auto-read / plan / ask；`/approvals yolo` → `yolo can only be chosen at startup…`。另有测试 `shift_tab_cycles_ask_auto_read_plan_and_never_reaches_yolo`。
- [x] **TUI 侧栏**：真实控制台在状态行上方显示 `tasks 0/3`、`[>] Reading the Calc source`、`[ ] …`（附录 C）。
- [x] **core 改动 = 主控授权的 SPI**（见 §3）；tui 新增 `sidebar`，另加 `onModeCycle`（见 §6）。
- [x] 测试清单：5A `invalid_lists_are_refused_*`（同时只能一项 in_progress、id 重复、控制字符）、`every_write_appends_a_tasks_entry_*`、`the_section_stays_within_its_budget`；5C 规则与 glob、非 0 回灌、超时（slow，真实 bash `sleep 20`/1s）、debounce、默认规则探测、未信任时忽略 verify.json；5D 写入被拦、只读 bash 白名单（13 例放行 / 19 例拒绝）、切换后放行、plan.md 只注入一次、always 答复不会绕过 PLAN。SPI：`SessionEntriesTest`（追加后 leaf 前移、fork 分支隔离、压缩后仍能读到）。

## 2. `build/check-budgets.sh`
```
pai-model LOC=2663 (max 4000)   pai-core LOC=4196 (max 5000)   pai-cli LOC=1669 (max 2000)   pai-tui LOC=990 (max 1500)
pai-ext-hitl LOC=1038  pai-ext-mcp LOC=2166  pai-ext-memory LOC=667  pai-ext-tasks LOC=380  pai-ext-verify LOC=412 (各 max 2500)
budgets OK
```

## 3. `git diff --stat M4..HEAD -- pai-core/src/main`
```
ExtensionContext.java | 39 +   ExtensionHost.java | 67 +-   SessionAccess.java (新) | 51 +   (3 files, +155 −2)
```
逐项对照授权：`option(String)`（主控裁决 1）。`Settings` 新增 `options` 和 `session` 两个分量，保留原 5 参构造器，另加 `withOptions`/`withSession`（裁决 1/2）。`appendEntry`、`latestEntry` 及其宿主回调 `SessionAccess`（裁决 2）。`tool(String)`（裁决 3）。除此之外 core 没有其他改动。`buildContext` 忽略 CustomEntry 的逻辑没动，`/tree` 原本就显示 `custom <type>`。

## 4. 签名异议
无。

## 5. SPI 漏洞
1. （已补）扩展无法写 session entry → `appendEntry`/`latestEntry`。
2. （已补）扩展无法调用其他工具 → `tool()`；它绕过整条 hooks 链，Javadoc 已写明只能传入非模型生成的命令。
3. 扩展之间无法共享 CommandGuard，也无法共享 `ReadOnlyCommands`（ext 不能互相依赖）。将来需要时可把 Guard 下沉为 core 的 `policy` 包，M6 再议。5B 的 `explore` 类型会碰到同一问题。
4. 扩展无法驱动 TUI：侧栏由 cli 用 `latestEntry("tasks")` 加 tui 的 `TaskList` 解析得到，等于 cli/tui 隐式知道 tasks 的 customType 和 data 格式（`TaskList.CUSTOM_TYPE/TOOL`）。审批模式也无法显示到状态行，只能靠 notify 打一行。
5. `CommandContext` 拿不到最近的 assistant 文本：`/plan save` 只能靠扩展自己监听 `MessageEnd` 并记下来。
6. 5B 需要列出全部工具（`allowedTools` 过滤），`tool(name)` 只能按名字查，不够用。

## 6. 偏离与理由
- tui 新增 `Renderer.onModeCycle(Runnable)`（默认空实现）。规格只写了 `sidebar`，但 Shift+Tab 必须有键绑定入口；这是最薄的做法：widget 回调 cli，cli 执行 `/approvals cycle`，ext-hitl 不知道键盘的存在。
- 用 `/approvals ask|auto-read|plan|cycle` 取代主控文中的 `/approval <mode>`，避免和既有 `/approvals` 两个命令并存；yolo 会被拒绝。`--approval` 增加了 `plan`。`PAI_APPROVAL` 改由 cli 读取后作为 option 传入（ext 不再读 System 属性或环境变量）。
- `ExtensionHost.Settings` 共 7 个分量，超过参数 ≤5 的约定：调用方一律走 5 参构造器 + `with*`，canonical 只是 record 的形式。
- verify 成功时追加一行 `[verify: …] passed`（规格只要求失败时回灌），用来告诉模型构建已恢复。debounce 已实现，无需退化为每次写入都跑：按本次 assistant 回复里最后一个匹配的调用触发，前面至少有一个成功时才跑。
- 主代码超出规格"合计 ≤600 行"的预估：tasks 301 + verify 335（不计注释），另 hitl/tui/cli 主代码 diff +633/−76、core +155/−2（含 Javadoc）。

## 7. 坑
- 中文 Windows 上，经 git bash 跑的 `mvn` 输出的 javac 中文消息是乱码（`???';'`）：`BashTool.shell()` 按 UTF-8 解码，子 JVM 输出的却是 GBK（pai-tools `BashTool.java` 的 `shell`）。行号和列号还在，模型仍能修好。
- PowerShell 的 `2>` 会把 stderr 按 120 列折行，debug-http 日志要先 `-join ''` 再匹配。
- `AttachConsole` 第一次调用失败过，重跑成功；`console.ps1` 现在会记录 `GetLastWin32Error`（脚本在 `D:\PaiCLI\tmp\m5live`，不在仓库里）。
- PMD 6：本阶段新增 11 个 excludes，共 87 个。

## 8. 建议（未做）
- `SessionEntries.latest` 每次都读整个 session 文件（每个请求一次、每次刷新侧栏一次），可以按文件大小加缓存。
- 状态行显示审批模式；GLOSSARY 增加 Task list / Plan mode / Verification rule。注意 GLOSSARY 把 "task" 列为 Run 的禁用词，而规格模块名就叫 `tasks`，需主控定。
- `ReadOnlyCommands` 连引号里的 `$`、`;` 也一律拒绝（如 `grep 'a$'`），偏保守；BashTool 的编码问题见 §7。

## 9. 给 M5.5 的输入
- 子代理可以用 `appendEntry` 在父 session 里记录子 session id。只读工具的筛选（effect + bash 白名单）在 hitl 里是 package-private，`explore` 类型要复用就得先解决 §5.3。
- debounce 的写法可复用：通过 `state.messages()` 的最后一条 AssistantMessage 拿到本回复的全部调用。`afterToolCall` 对被拦的调用同样会被调用。

## 附录
**P3C**：reporting 模式；maven-pmd-plugin 3.13.0、PMD 6.21.0、p3c-pmd 2.1.1。excludes 共 87 个（+11：tui 1、hitl 2、tasks 2、verify 4、cli 2）。Priority 2 共 9 条，全是既有嵌套 record 被 `LowerCamelCaseVariableNamingRule` 误报，本阶段新增 0 条。
**行数**（不计空行和注释，主 / 测试；设备无 cloc，用同口径脚本）：model 1892/1151，core 2636/2110，tools 428/286，tui 741/339，ext-mcp 1641/1521，ext-hitl 829/680，ext-memory 588/293，ext-tasks 301/121，ext-verify 335/236，cli 1431/1297。
**A. 多文件任务（Plain + `PAI_INTERACTIVE=1`）**
```
! verify: on write|edit **/{*.java,pom.xml} -> mvn -q compile
ok todo_write {"items":[{"id":"1","content":"Read existing Calc.java, G... / ok edit src/main/java/demo/Calc.java (5.08s)
30abf945 edit: … 9	        return a * b … | [verify: mvn -q compile] failed (exit 1), output tail: [ERROR] …Calc.java:[9,21] ???';'
> edit Calc.java (0.00s) > edit Greeter.java (3.19s)  f2160478 edit: … | [verify: mvn -q compile] passed
> edit CalcTest.java (0.00s) > write GreeterTest.java (4.07s) | [verify: mvn -q compile] passed;  ok $ mvn -q test (exit 0)
custom tasks entries: 5;  /todo → Task list (7/7 completed)
```
**B. 计划模式请求体**：`req1-3 plan-mode=True tools=[load_skill,read,bash,todo_write,todo_read]`；`req4-5 plan=True … write,edit`；`req6-9 plan=False`；日志中 key 形态字符串 0 个。
**C. 真实控制台**：`approvals: mode plan / approvals: mode ask / approvals: mode auto-read / approvals: mode plan`；侧栏 `tasks 0/3 · [>] Reading the Calc source · [ ] Add a divide method to Calc · [ ] Add a test covering the divide method`，下面是状态行 `openai-compat/deepseek-chat | ctx 1% | … | idle`。
