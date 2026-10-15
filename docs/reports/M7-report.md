# M7 实施报告

- 分支：`impl/M7`，共 6 个 commit（`e02d161..9bd56fa` 和本报告的 docs commit），基于本地 `main`，也就是 M6 加上 docs commit `6670566`（ADR 0002/0003 修订和 M7 规格）。未 push，也未合入。
- CI：没有 remote，只在本机跑了 `mvn -B -ntp verify`，结果 BUILD SUCCESS，759 个测试（M6 是 650 个；`BenchTest` 默认 skipped），budgets OK。
- 实施 session：Claude Opus 5.5，2026-10-15。

## 1. 验收清单

- [x] **`AgentEnd.reason` 在 7 种情形下各正确一次（FakeProvider 脚本）**。`pai-core` 的 `loop/TerminationReasonTest` 每种原因一个用例：
  - COMPLETED：回复中没有工具调用。
  - MAX_TURNS：`maxTurns=1`，回复带工具调用。
  - ABORTED：token 已取消，provider 回复 ABORTED。
  - PROMPT_TOO_LONG：两种都测了，不重试，以及重试后仍超长。
  - MAX_OUTPUT_TOKENS：连续 3 次 LENGTH 且带工具调用。共 3 次请求，最后一次请求里有 2 条 continue 消息，还有一个 Error 事件。另测只截断一次的情况：续写后正常 COMPLETED。
  - PROVIDER_ERROR：HTTP 401。
  - HOOK_ERROR：transformContext 抛异常，0 次请求。

  同一个类还断言了 `TurnStart.estimatedTokens`：第二轮比第一轮大，且大于 0；旧构造器默认值为 0 和 COMPLETED。

  阶段顺序和 no-op 语义在 `loop/ContextStageTest`：`micro:3/3, summary:2/3` 说明阶段拿到的是上一阶段的输出，而 `state.messages()` 是保留的上下文；投影不替换保留的上下文；SUMMARY 替换保留的上下文，并带上 summary；抛异常的阶段被跳过；`requestSent` 收到的是最终请求。

  熔断在 `CompactingHooksMicroTest.three_failed_summaries_in_a_row_stop_automatic_compaction_until_a_forced_one_succeeds`：自动压缩 20 次只调了 3 次 summarizer，notice 只发 1 条；force 仍然执行；force 成功后自动压缩恢复。
- [x] **真实 DeepSeek：PLAN 下 `bash ls -R | head` 免审、`bash echo x > f` 被拦、`bash cat ~/x` 转审批，全部由 `effect(input)` 驱动；`ReadOnlyCommands` 已删除**。
  - 命令：`run.ps1 -Name plan -Extra "--approval plan"`，`PAI_INTERACTIVE=1`。stdin 是 `D:\PaiCLI\tmp\m7live\plan-input.txt`：三条"只用 bash 跑这条命令"，然后一行 `n` 和 `/quit`。`plan.log` 原文如下：
  ```
  > $ ls -R | head
  ok $ ls -R | head (exit 0, 0.68s)
  > $ echo x > f.txt
  x $ echo x > f.txt (tool call blocked: plan mode: read-only; explore with read-only tools and rep..., 0.00s)
  note: this read-only command may read outside the working directory; "always" is not remembered
  tool bash (execute)
  $ cat ~/x
  allow? y = yes, n = no, a = always for bash in this session, A = always, saved as a rule (shown before saving)
  x $ cat ~/x (tool call blocked: user denied, 0.00s)
  ```
  - `git rm` 已删除 `ReadOnlyCommands.java`，`rg ReadOnlyCommands` 在代码里没有匹配。PLAN 下的判断是 `ApprovalHooks.beforeToolCall` 里的 `Tool.effectOf(tool, input) == READ_ONLY`，路径检测是 `PathGuard.commandMayLeaveCwd`。
  - 词法：`pai-tools/ShellLexerTest` 有 23 条只读样例和 72 条 EXECUTE 样例，后者包括 M4/M5 审查里所有绕过样例（引号/反斜杠拼接、`$(…)`、反引号、`iex`、包装器等），以及重定向、进程替换、分组、注释、控制字符、NBSP、缩写长选项 `--outp`。EXECUTE 样例在 bash 和 cmd 两种模式下都断言。
- [x] **`A` 持久化 `bash: mvn *` 后新会话不再询问；`deny git push*` 不可批准**。live 跑了两个会话，用同一个 `PAI_HOME`（`perm-home`）。
  - 会话 1（`perm1.log`）：
  ```
  $ mvn -v
  allow? ... A = always, saved as a rule (shown before saving)
  save rule "allow bash mvn *" to D:\PaiCLI\tmp\m7live\perm-home\ext\hitl\permissions.json? later sessions will not ask
  ! approvals: saved "allow bash mvn *"
  ok $ mvn -v (exit 0, 2.95s)
  ```
  - 会话 2（`perm2.log`）：`mvn -v` 没有提示，直接 `ok $ mvn -v (exit 0, 1.52s)`。执行 `/approvals deny bash git push*` 后：
  ```
  x $ git push origin main (tool call blocked: policy: permission rule "deny bash git push*" (blocked by ..., 0.00s)
  ```
  - 这时 `permissions.json` 的内容是 `{"allow":[{"tool":"bash","pattern":"mvn *"}],"deny":[{"tool":"bash","pattern":"git push*"}]}`（pretty 格式）。
  - 单测在 `PermissionsTest`，10 个用例，覆盖：glob（Windows 下忽略大小写）、allow 不匹配复合命令、deny 匹配命令片段、项目级规则不能放宽用户级 deny、工具级 deny 不提供给模型、持久化读写且不留临时文件、拒绝或无法生成规则时只放行这一次、控制字符/方向字符被拒、`/approvals list|allow|deny|forget`、未信任项目忽略项目级文件。
- [x] **8k 窗口任务：压缩后模型未重读即能继续改 `Files touched` 里的文件**。live 运行 `k8b`：`models.json` 里 deepseek-chat 的 window 设为 8000，`--approval yolo`，`PAI_DEBUG_HTTP=1`。stdin 是 `k8b-input.txt`：改 Main.java（harbor→lighthouse）；三个无工具的小问题，把这次改动挤出保留区；`/compact`；改 Main.java（lighthouse→beacon）。session jsonl 中依次是：
  ```
  0661580e COMPACTION  Files touched: - `Main.java`: read and edited; line 3 now prints `lighthouse` ...
  d231c2aa parent 0661580e user <restored-files>…<file path="Main.java">…println("lighthouse");…</file></restored-files>
  1d8cb71d user  In Main.java, change the printed word lighthouse to beacon. ...
  203fd76e assistant tool_call edit {"path":"Main.java","oldText":"        System.out.println(\"lighthouse\");","newText":"        System.out.println(\"beacon\");"}
  ```
  压缩之后没有任何 `read` 调用，edit 一次成功，Main.java 最终打印 `beacon`。请求体（脱敏日志）里在 `"8"` 那条回复之后是 `{"role":"user","content":"<restored-files>\nCurrent content of files the summary lists as touched, latest first; edit them directly, there is no need to read them again.\n<file path=\"Main.java\">\n…`。日志里 key 形态的字符串和 Authorization 头都是 0 个。

  另外记一次没恢复的情况，见 §7。第一次 live（`k8`）里，摘要的 Files touched 只列了 README.md 和 notes.txt，两份各约 600 token，超过了摘要后剩下的空间（约 150 token），所以没有恢复，是按设计跳过；Main.java 的改动那时还在保留区里，所以不需要恢复。
- [x] **`docs/reports/bench-M7.md` 有五项数字；`docs/reports/M7-report.md`**。五项分别是：冷启动 1031 ms；`--resume` 1000 条 entry 端到端 858 ms，其中 store 部分 17.5 ms；一次 read 工具的 loop 开销 1.63 ms；微压缩 200 条消息 0.127 ms；全量摘要前后 40382→2085 token。附加一项侧栏查找：全量重读 4.35 ms，增量解析 1.20 ms。
- [x] **core 改动仅限 7A/7B 授权项**：见 §3。此外 compaction 包有格式化和预算相关的改动，7D 在 compaction 里加了一个回调，详见 §6。ADR、`docs/00|01|02`、phases、GLOSSARY、HANDOFF、DECISIONS-LOG 都没有改。

## 2. `build/check-budgets.sh`
```
pai-model LOC=2663 (max 4000)
pai-core LOC=4992 (max 5000)
pai-cli LOC=1911 (max 2000)
pai-tui LOC=1005 (max 1500)
pai-ext-hitl LOC=1400 (max 2500)
pai-ext-mcp LOC=2174 (max 2500)
pai-ext-memory LOC=1187 (max 2500)
pai-ext-tasks LOC=384 (max 2500)
pai-ext-verify LOC=628 (max 2500)
budgets OK
```
core 只剩 8 行余量。为腾空间做了这些：删了三个只有测试在用的构造器（见 §6-12）；`ToolEffects` 并入 `Tool.effectOf`；两个阶段类改为嵌套类；compaction 和 loop 中 17 处单句 Javadoc 由 3 行合成 1 行（只改格式）；精简了我新写的 Javadoc。没有删测试，也没有改预算脚本。最大的文件是 `Repl.java` 404 行、`CompactingHooks.java` 403 行（非空行分别在 400 以内，`CompactingHooks` 为 368 行）；TODO 为 0。

## 3. core 改动逐文件（`git diff --stat main..impl/M7 -- pai-core/src/main`）
- `tool/Tool`（7B）：新增默认方法 `effect(JsonNode)`、`concurrencySafe(JsonNode)`，以及 fail-closed 的静态方法 `effectOf(Tool, JsonNode)`：抛异常或比 `effect()` 更危险时，warn 并返回 `effect()`。
- `internal/ToolRunner`（7B）：并行分组改用 `concurrencySafe(input)`；这个方法抛异常时按不安全处理。
- `loop/TerminationReasonEnum`、`loop/ContextStage`（7A，新文件）。
- `loop/AgentEvent`（7A）：`AgentEnd` 加 `reason`，`TurnStart` 加 `estimatedTokens`，旧构造器都保留；更新了 `ContextCompacted` 的 Javadoc。
- `loop/AgentConfig`（7A）：新增分量 `stages` 和 `withStages`。
- `loop/AgentLoop`（7A）：终止原因、阶段管线、MAX_OUTPUT_TOKENS 重试；TurnStart 携带最终请求的估算值；把最终请求交给 `requestSent`。
- `compaction/CompactingHooks`（7A 拆阶段 + 7D）：不再实现 `AgentHooks`；`stages()` 返回嵌套的 `MicroCompactStage` 和 `SummaryCompactStage`；删除 `withListener`，换成 `withHost(notices, restore)`；加了熔断和 restore 回调；新增静态方法 `summary(List)`。
- `compaction/SummaryValidator`、`ModelSummarizerImpl`（M6 输入项）：判断 write 改为 `effectOf == WRITE_FS`，通过 `ToolRegistry` 查工具；新增 `files(summary)`。
- `internal/LoopSnapshot`：删除两个只有测试在用的便捷构造器。
- `compaction/{CompactionPolicy, MicroCompactionPolicy, Summarizer, TokenEstimator}`：只改 Javadoc 格式。

## 4. 签名异议
- `ContextStage` 在规格的 `name/apply` 之外加了三个默认方法：`kind()`、`summaryOf(List)`、`requestSent(ChatRequest)`。没有它们，"阶段返回值携带 kind 与 summary"和"requestOverhead 含补进的工具"都做不到，因为签名规定 `apply` 返回的是 `List<Message>`。请主控确认，或者改成返回 record。
- `AgentConfig` 的 6 参构造器在 M2 以来是 canonical，现在不再存在：canonical 变成了 7 参，另外保留 5 参和 `of`。原因见 §6-12。

## 5. SPI 漏洞
1. `Tool.concurrencySafe(JsonNode)` 的默认实现按 ADR 写成 `effect(input) == READ_ONLY`，没有调用 `concurrencySafe()`。这样一来，已经覆写了 `concurrencySafe()` 返回 false 的工具（`TodoTools`、`McpTool`、`ReadResourceTool`）会悄悄变成可以并行。已在这三个工具里补了 `concurrencySafe(JsonNode)` 覆写，但第三方扩展同样会踩到。建议在 ADR 0002 里写明。
2. hitl 的 PLAN/AUTO_READ 路径检测是对原始命令文本做正则，它之所以可靠，依赖 pai-tools 的规则"含引号/反斜杠/`$` 的命令一律不判只读"。这是跨模块的不变量，没有类型来保证（`ShellLexer` 是 package-private）。建议 M7.5 把"命令词"或"可能访问的路径"作为工具的自描述暴露出来，或者把路径检测下沉到 core policy。
3. `UserInteraction.ask` 的实现忽略大小写，这一点没有写进合同，导致 `a` 和 `A` 冲突。已把 `TerminalInteractionImpl` 改为先精确匹配，建议补进 ADR 0005。
4. 恢复文件的消息是一条普通 `UserMessage`，下一次摘要会把它当用户内容来总结。将来可能需要一种 meta 消息类型。

## 6. 偏离与理由
1. **提交顺序**：先做的 7B，然后 7A 和 7D 合在一个 commit 里，然后 7C，最后两个小修。7A 和 7D 都改了 `CompactingHooks`、`Main` 和 `Repl`，而且 core 预算必须连同 7D 的回调一起算，分成两个 commit 时中间那个编译不过。
2. **MICRO 事件每次请求都发**（ADR 0003 M7 (3)："阶段生效即发一次"）。M6 的做法是只在被裁的数量变化时发。`CompactingHooksMicroTest` 里的事件断言已移到 `CompactionInTheLoopTest`（真实 loop），断言没有放松：每个请求恰好 1 个 MICRO、没有 CompactionEntry、只有 1 个 SUMMARY 且带 summary、`messagesAfter` 与请求一致、低于触发线时没有事件。原测试还加了一条断言：同一个投影第二次请求时结果相同。M6 的三条修复语义（只扣新裁剪部分并以 `sentKnown` 兜底、第二档滞回、失败退避 3 turn 且 force 不受限 / `reset()`）对应的用例全部原样通过。
3. **`CompactingHooks` 保留了名字，但不再是 hooks**。它成了两个阶段背后的状态，阶段类以嵌套类的形式放在里面，这样省掉了两份文件头和 import。摘要阶段不使用它的输入（微压缩后的投影），而是对 `state.messages()`（保留的上下文）做压缩；做判断时仍按投影大小。原因是压缩要求上下文与 session 投影逐条 equals。
4. **requestOverhead 由 `requestSent` 用最终请求测量**（含 `withToolsRegisteredSince` 补进的工具；测试见 `the_overhead_counted_is_that_of_the_request_finally_sent_late_tools_included`）。代价：会话的第一个请求做微压缩判断时 overhead 按 0 算；摘要判断与 M6 一样，用的是上一次请求的值。
5. **TurnStart 在管线之后、请求发出之前发出**，这样才拿得到估算值。transformContext 失败时没有 TurnStart；overflow 重试会再发一次同一个 index 的 TurnStart。
6. **含引号或反斜杠的命令仍判为 EXECUTE**（M5 的裁决）。词法器能正确解析 `grep "a;b" f`，但仍判为 EXECUTE，原因见 §5-2。
7. **AUTO_READ 下 `bash ls` 这类只读命令同样免审**（ADR 写的是"PLAN/AUTO_READ 据此精确化"），并且同样做路径检测：可能访问 cwd 以外时每次都询问，"always" 不记住。
8. 补了三个 ext 工具的 `concurrencySafe(JsonNode)` 覆写（ext-tasks 和 ext-mcp 各改了几行），原因见 §5-1。
9. `SummaryValidator` 判断"Files touched 为空"时，改为与 `normalize` 一致，即"带路径的 bullet 行"。不带 bullet 的行不再算文件；`none.` 仍算空。
10. **7D 文件恢复放在 cli 的 `FileRestorer`**。core 只负责提取路径、调用回调、把结果记成 MessageEntry。真实路径解析的做法与 PathGuard 相同：用 `toRealPath`，必须在 cwd 内。恢复的消息挂在 CompactionEntry 下面，排在新上下文的末尾（session 投影的顺序决定了它只能在末尾）。总量取 min(25k, (阈值 − 摘要后的估算) / 2)，不是固定的 25k：8k 窗口下阈值只有 4k，固定 25k 会让下一次请求立刻又触发压缩。
11. `A` 选项要求 `TerminalInteractionImpl` 先做精确匹配（tui 改了 4 行），原因见 §5-3。`ProjectTrust` 把 `.pai/permissions.json` 加入了信任询问的触发清单（cli，1 行）：项目里的 allow 规则可以免审批，属于"改写安全行为的配置"。
12. 为了预算，删除了 `ModelSummarizerImpl(Supplier)`（不传 registry 时等于"没有任何工具会写文件"，容易误用）、`AgentConfig` 的 6 参构造器，以及 `LoopSnapshot` 的 5 参和 7 参构造器。测试改为调用 canonical 构造器，没有删测试。
13. permission 规则的语义：allow 规则永远不匹配复合命令（含 `; & | ( ) $ ` < > 引号 \`），deny 规则会对整行和各个片段分别匹配。只对 bash 根据首词生成规则（`mvn *`）；复合命令或首词不是普通字符时不生成规则，这次调用只放行一次，并给出 notice。

## 7. 坑
- `device_stage_files` 只能拉取连接目录以下 ≤7 层的文件，所以源码拉不回容器。我的做法是：电脑上执行 `git diff --binary --output=…patch 6670566`，把 patch 拉回容器，然后 `git apply` 到 470b5fb 上（`scratchpad/pull.sh`）。推送到电脑时每次用唯一的 stagedPath，并在 `mvn spotless:apply` 之前核对 MD5（`verify.ps1 -Expect`）。
- 外层 shell 会吞掉 `-Command` 里的 `$_` 和 `$null`，所以一律写成 `.ps1` 用 `-File` 跑；在 `-File` 模式下，`-Files a,b,c` 传进去的是一个字符串（`rm.ps1` 里自己按逗号拆）。
- 电脑上的 `bash` 是 WSL 的 bash（`C:\WINDOWS\system32\bash.exe`），里面没有 Java 和 Maven，所以 `build/bench.sh` 只在容器里做了 `bash -n` 语法检查，数字全部来自 `bench.ps1`。
- 预算脚本把注释也计入行数（`grep -cve '^\s*$'`），core 余量很小，Javadoc 也占预算。
- 8k live：恢复只在被编辑的文件进入摘要段时才会发生；如果编辑还在保留区里，模型本来就能看到原文，也就不需要恢复（`k8.log`、`k8b.log`）。
- 读 session jsonl 时，工具调用块的 `type` 是 `tool_call`，不是 `toolCall`。

## 8. 建议（未做）
- `CompactingHooks` 改名为 `Compaction`（它已经不是 hooks 了）。`FileRestorer` 和 PathGuard 的真实路径解析是重复的，可以和 M7.5 的写冲突预检一起下沉到 core policy。
- 只读表的残余风险：cmd 回退时，白名单命令名可能命中 cwd 里的同名 `.bat`；不受信任仓库的 `.git/config` 里 `core.fsmonitor` 或 `diff.external` 可以让 `git status/diff` 执行程序。可以在 cmd 下只放行 `dir`/`type`，并给 git 加 `-c core.fsmonitor= --no-ext-diff`（需要改写命令，所以没做）。
- GLOSSARY 新词（请主控加）：Termination reason（`TerminationReasonEnum`）、Context stage（`ContextStage`）、Permission rule（`permissions.json` 的 allow/deny）、Restored files（`<restored-files>` 消息）、Read-only table（`read-only-commands.txt`）。
- 熔断目前只看连续失败次数；如果摘要成功但压缩之后仍超阈值，就会每 turn 都压缩一次。可以考虑加一条规则：压缩后仍超阈值也算一次失败。

## 9. 给 M7.5 的输入
- explore 子代理的只读过滤直接用 `Tool.effectOf(tool, input) == READ_ONLY`。bash 的只读判断已有表驱动测试，但"命令访问哪些路径"仍然只是 hitl 里的正则（§5-2）。子代理如果要放宽到"只读 bash"，需要先解决这一点。
- 侧链 transcript 可以复用 `SessionEntriesImpl` 的增量解析，以及 `ContextStage` 的事件约定（子代理的压缩也由 loop 发出事件）。
- 写冲突预检的真实路径锁可以与 `FileRestorer.inside` 共用实现。

## 10. 审查修复
在 `impl/M7` 上按主控审查（结论"修后合入"）逐项修复。提交：`d9a8397 docs: ADR 0002/0003 and decisions log after the M7 review; core budget 5500`（主控写进工作区的 `build/check-budgets.sh`、`docs/adr/0002`、`docs/adr/0003`、`docs/DECISIONS-LOG.md`，原样提交），`96a3d37 fix(review): …`（P0-1…P2-6），以及本节和 live 7 中发现的估算问题（见 P2-7）所在的修复提交。`mvn -B -ntp verify` 全绿，测试 837 个（原 759 个）。

- **P0-1 未加引号的 `*` `?` `[` 一律 EXECUTE。** 词法器从 pai-tools 的 `ShellLexer`（已删）移到 core，成为公开的 `ShellCommands.simpleCommands(String)` / `simpleCommands(String, boolean cmdShell)`，返回 `Optional<List<List<String>>>`。只要行里有 `' " \ $ ` < > * ? [ ] ( ) { } #`、控制字符、空命令，或者在 cmd 下有 `% ^ !`，就返回 empty（不干净）。`ReadOnlyRules` 只在每个简单命令都只读时才判只读。`find . -name *.java` 已从只读样例移到 EXECUTE 样例，并新增 `rg needle *`、`git diff *`、`cat *`、`find * -name x`、`ls [a-z]*`、`grep x ?.txt`、`rg --pre-glob *.gz foo`（审查给的样例是 `rg TODO *`，但预算脚本禁止 `.java` 里出现 TODO 字样，所以改成了 needle）。测试：`ReadOnlyRulesTest.read_only_command_lines`、`anything_else_executes`、`the_bash_tool_reports_read_only_calls_by_their_command`（含并发断言）；`ShellCommandsTest.plain_lines_split_into_simple_commands_and_words`、`anything_else_is_unclean`、`the_cmd_shell_also_refuses_its_variables_and_escapes`。
- **P1-1 只读命令的路径检测。** 正则改为 `\s-[A-Za-z]+/`。新增 `PathGuard.commandOutside(command)`：每个非选项词、`--x=` 后面的值、紧贴在 `-x` 后面的值，都通过 PathGuard 的真实路径解析（含符号链接）检查是否在 cwd 内；词为 `-`（stdin）或者行不干净时，也视为可能读到 cwd 以外。`ApprovalHooks.readCommand` 只要正则或 `commandOutside` 命中，就询问一次（只放行这一次）。Windows 上带 `:` 的词不是合法路径，所以另外检查最后一个 `:` 之后的部分（`cat a:/etc/x` 会被询问）。测试：`ReviewFixesTest.a_read_command_whose_word_links_outside_is_asked_about`（`notes` → 外部文件的符号链接）、`read_commands_that_may_read_elsewhere_are_asked_about`（`grep -rf/etc/passwd .`、`cat -`、`cat ../x`、`grep --file=/etc/x a`、`cat a:/etc/x`）、`read_commands_inside_run_without_asking`。
- **P1-2 git 读命令只在受信任项目里免审。** `ApprovalHooks` 新增 4 参构造器 `(Parts, Permissions, Consumer<String>, boolean projectTrusted)`，`HitlExtension` 传入 `ctx.projectTrusted()`；在不受信任的项目里，只要有简单命令运行 git，就询问一次（`GIT_UNTRUSTED_NOTE`）。测试：`ReviewFixesTest.git_reads_are_free_only_in_a_trusted_project`（不受信任的 AUTO_READ 会问，PLAN 没有 ui 时拦截；受信任的不问）。
- **P1-3 deny 规则按词序列匹配。** 新增 hitl 内部类 `CommandWords`：基于 `ShellCommands.simpleCommands` 把每个简单命令规整化，去掉 `env/command/builtin/time/nice/nohup/exec/sudo/doas` 及其选项、`VAR=x`、shell 关键字，程序名去掉目录和 `.exe`。deny 规则同时做三种匹配：原文 glob、词序列（规则首词可以匹配程序名，其余的词按顺序出现即可，所以 `git -C . push` 也会命中）、规范化后的路径。当行不透明（不干净、或者把命令交给 `sh -c`/`eval`/`xargs`/`python*` 等解释器）且存在任何 bash deny 规则时，`Permissions.uncertain` 为真：YOLO、会话 `a`、allow 规则都不再自动放行，每次至少询问一次，没有 ui 时拦截（`UNCERTAIN_NOTE`）。测试：`ReviewFixesTest.deny_rules_cannot_be_bypassed`（审查给的 10 个样例加上 `sudo -u x git push origin`、`nice -n 5 git push`、`sh -c 'git push'`、`echo x | xargs git push`，共 14 个，在 YOLO 和 AUTO_READ 下分别验证）、`an_unclear_command_is_asked_once_even_with_always_and_allow_rules`、`without_bash_deny_rules_unclear_commands_follow_the_mode`。代价：词序列匹配会多拒，比如 `echo git push` 也会命中 `git push*`。我认为这个方向（多拒）是可以接受的。
- **P1-4 `concurrencySafe(input)` 默认值。** 改为 `concurrencySafe() && Tool.effectOf(this, input) == READ_ONLY`；`BashTool` 覆写为只看 `effectOf`；删除了 `TodoTools`、`McpTool`、`ReadResourceTool` 里的三个覆写（§6-8 和 §5-1 因此失效）。测试：`PerInputEffectTest.a_read_only_tool_that_opts_out_of_concurrency_is_not_run_in_parallel`；`ReadOnlyRulesTest.the_bash_tool_reports_read_only_calls_by_their_command`。
- **P1-5 摘要后仍超阈值算一次失败。** `Compaction.compact`：摘要成功后，如果估算仍高于阈值，自动压缩计一次失败（达到 `MAX_FAILURES` 时给出 notice，文字改为"…summaries that failed or left the context too large; /compact still works…"）；只有 forced 且压缩后低于阈值才清零。测试：`CompactionMicroTest.summaries_that_leave_the_context_too_large_count_as_failures`、`three_failed_summaries_in_a_row_stop_automatic_compaction_until_a_forced_one_succeeds`（加了"forced 成功但仍超阈值时仍停着"这一段）。
- **P2-1 `effectOf` 取更危险的一侧。** 声明为 READ_ONLY 的工具，如果按输入细化成更危险的效果，就用细化后的结果；其余工具仍然是"只能变安全"。细化抛异常或返回 null 时：READ_ONLY 工具得到 UNKNOWN，其余工具用声明值。测试：`PerInputEffectTest.per_input_effects_may_only_be_safer_and_fail_closed`（名字沿用，断言改为 READ_ONLY→EXECUTE 得到 EXECUTE、null 得到 UNKNOWN）。
- **P2-2 `lengthRetries` 只计连续截断。** 测试：`TerminationReasonTest.only_cut_offs_in_a_row_count_against_the_retries`。
- **P2-3 FileRestorer 排除秘密文件。** `.env*`、`*.pem`、`*.key`、`*.p12`、`*.jks`、`id_*`、`*credential*`、`*secret*`、`*.kdbx`，列出的名字和真实路径的文件名都会检查；跳过时通过 notifier 提示 "compaction: not restoring X (looks like a secret)"。构造器改为 `FileRestorer(Path, Consumer<String>)`。测试：`FileRestorerTest.files_that_look_like_secrets_are_not_restored_and_the_user_is_told`。
- **P2-4 路径类 allow 规则。** 带路径参数的工具，按 cwd 内规范化后的相对路径匹配（`src/../.git/hooks/pre-commit` 不会命中 `src/*`，cwd 以外的路径一律不命中）。`Permissions` 构造器多一个 `cwd` 参数，`load(configDir, projectFile, cwd)`；`rules()` 加 synchronized 并返回 `List.copyOf` 快照，写操作同步。测试：`ReviewFixesTest.path_allow_rules_match_the_normalized_path_inside_the_working_directory`，以及 `PermissionsTest` 全部用例。
- **P2-5 不为包装器和解释器生成规则。** 首词是赋值，或者是 env/sudo/sh/bash/pwsh/powershell/cmd/python*/node/npx/xargs/eval/exec/command（以及其他包装器和解释器）时，`proposedPattern` 返回 empty，这次调用只放行一次。测试：`ReviewFixesTest.no_rule_is_proposed_for_wrappers_and_interpreters`。
- **P2-6 改名。** `CompactingHooks` → `Compaction`，测试改为 `CompactionTest`、`CompactionMicroTest`。cli 的 `Repl` 里原来有个嵌套 record 也叫 `Compaction`，会和它冲突，所以改为 `Repl.Compacted`（`Commands`、`ReplTasksTest` 随之修改）。
- **P2-7 live：8k 窗口，loop 内自动 SUMMARY 后恢复文件，模型不重读直接 edit。** 先跑了一次，结果发现了一个问题：估算器用"最后一条回复报告的用量"校准，而摘要保留下来的那条回复，报告的是压缩**之前**的大小。所以摘要后的估算等于摘要前（日志里是 `4483 tokens estimated, 4483 after`），P1-5 会把每一次摘要都当成失败，3 次后熔断；同时恢复空间算成 0，没有 `<restored-files>`，模型只能重读 Main.java。修复：`Compaction` 记住摘要保留下来的那些 assistant 消息（`staleUsage`），估算时去掉它们报告的用量，只按字符算，直到出现更新的回复。测试：`CompactionMicroTest.after_a_summary_the_size_reported_before_it_is_not_counted`（恢复空间 > 500，并且不会紧接着再摘要一次；修复前空间为 0）。
  修复后的 live（`k8c`）：脚本 `k8c-setup.ps1` 和 `run.ps1`，日志 `k8c.log`，fixture `k8c-proj`，home 为 `k8c-home`（window 8000，阈值 4000），`--approval yolo`，`PAI_DEBUG_HTTP=1`。输入依次是：把 Main.java 的 harbor 改成 lighthouse；读 a/b/c.txt（各约 1800 字符）并回答首词；不用工具问 2+2；把 lighthouse 改成 beacon。loop 内自动摘要 4 次（`compacted 5 of 11 … 4418 → 2845 after`、`2 of 10 … 4422 → 2953`、`6 of 12 … 4510 → 1872`、`4 of 10 … 4418 → 2855`），都没有触发熔断。最后一个 turn 的第一个请求之前，loop 自动摘要（session 第 21 行 CompactionEntry），第 22 行是 `<restored-files>`，内容为 c.txt、b.txt，以及 `Main.java`（内容是 `System.out.println("lighthouse");`）。第 23 行模型直接 `edit Main.java`（oldText 为 lighthouse 那一行），中间没有任何 read，Main.java 最终打印 `beacon`。日志里 Authorization、`sk-`、`Bearer`、`api_key` 都是 0 次。注意：8k 窗口下 system 加工具定义大约 2.7k token，再加上保留区里的三份文件，所以每个 turn 都会摘要一次。这是窗口太小所致，不是熔断问题。

**新增公开成员**（补充附录）：core `ShellCommands.simpleCommands(String)`、`ShellCommands.simpleCommands(String, boolean)`；`Compaction`（原 `CompactingHooks`，公开成员不变）；`Tool.concurrencySafe(JsonNode)` 默认语义变化；`Tool.effectOf` 语义变化（P2-1）。ext-hitl：`ApprovalHooks(Parts, Permissions, Consumer<String>, boolean)`、`ApprovalHooks.GIT_UNTRUSTED_NOTE`、`UNCERTAIN_NOTE`；`Permissions(Optional<Path>, Optional<Path>, boolean, Path)`、`Permissions.load(Path, Optional<Path>, Path)`。cli：`FileRestorer(Path, Consumer<String>)`。pai-tools 删除了 package-private 的 `ShellLexer`。

**偏离**：
1. `ShellCommands` 放在 core（`dev.pai.core.tool`），没有放在 pai-tools。原因是 ArchUnit 禁止 ext 依赖 `dev.pai.tools..`，而 hitl 需要同一个词法器。§5-2 提到的跨模块不变量由此有了类型保证。
2. 审查样例 `rg TODO *` 改成了 `rg needle *`（原因见 P0-1）。
3. P2-7 中发现的校准问题，是在审查条目之外修的。

**预算**（主控已把 core 上限提到 5500，没有为预算删 API 或压注释）：
```
pai-model LOC=2663 (max 4000)
pai-core LOC=5105 (max 5500)
pai-cli LOC=1930 (max 2000)
pai-tui LOC=1005 (max 1500)
pai-ext-hitl LOC=1614 (max 2500)
pai-ext-mcp LOC=2166 (max 2500)
pai-ext-memory LOC=1187 (max 2500)
pai-ext-tasks LOC=380 (max 2500)
pai-ext-verify LOC=628 (max 2500)
budgets OK
```
§8 第 1 条里的改名和第 4 条（压缩后仍超阈值算一次失败）已在本节落实。

## 11. 复审残留
复审结论"可合入"后，主控又列了 4 条残留，都在一个 commit 里修掉（主控写进工作区的 `docs/01-code-style.md`、`docs/GLOSSARY.md`、`docs/DECISIONS-LOG.md` 单独作为一个 `docs:` commit）。
1. **cmd 回退下的 deny 绕过。** 在 Windows 上，shell 工具可能退回到 `cmd /c`，而 cmd 会剥掉 `^`、展开 `%x%`/`!x!`；`CommandWords`（`commands`/`opaque`/`runs`）以前永远按 bash 解析。现在这几个方法多一个 `windows` 参数，传给 `ShellCommands.simpleCommands(line, windows)`，复用 cmd 模式把 `% ^ !` 判为不干净的逻辑；`Permissions` 用它原来的 Windows 开关（构造器第 3 个参数，从 `ignoreCase` 改名为 `windows`）。这样 `git pu^sh`、`git p%x%ush` 在 Windows 上是 uncertain，有 bash deny 规则时不会被自动放行。`WRAPPERS` 新增 `timeout`、`stdbuf`、`watch`、`ionice`、`chrt`，包装器参数也接受 `5s` 这样的时长：现在不会再生成 `timeout *` 这种等于放行一切的规则，`timeout 5s git push` 也会被 `git push*` 拒绝。测试：`ReviewFixesTest.on_windows_cmd_escapes_and_variables_hide_the_command`（`git pu^sh`、`git p%x%ush`、`git push!x!`、`timeout 5s git push`，YOLO 下拦截；bash 模式下 `^` 仍是普通字符）、`no_rule_is_proposed_for_wrappers_and_interpreters`（加了 timeout/stdbuf/watch/ionice/chrt）、`deny_rules_cannot_be_bypassed`（加了 `timeout 5s git push`）。
2. **短选项粘写值只由字母组成时不检查。** 对 `-xYYY`，除了原来"剥掉所有字母"得到的值，再把剥掉第一个字母后的剩余部分作为候选路径检查一遍（`grep -flnk .` 会检查 `lnk`）。测试：`ReviewFixesTest.a_link_glued_to_a_short_option_as_letters_only_is_asked_about`（`lnk` 是指向 cwd 外的符号链接，会询问；`-flocal` 指向不存在的 `local`，不询问）。
3. **`<restored-files>` 越积越多。** core 的 `SessionTree` 新增公开成员 `RESTORED_FILES_TAG = "<restored-files>"` 和 `restoredFiles(Message)`。摘要时，保留区里旧的 restored-files user 消息会被丢掉（它们是派生内容，可以从 CompactionEntry 重建），只留下本次新恢复的一条；session 投影对 CompactionEntry 之前的保留段做同样的过滤，所以 `buildContext` 和内存里的上下文保持一致，`--resume` 后也是如此。`FileRestorer.OPEN` 改为以这个常量开头。测试：`CompactionMicroTest.a_second_summary_drops_the_files_restored_by_the_first`（连续两次摘要后，上下文里恰好一条 restored-files，就是第二次的那条，并且与 `buildContext` 相等）。
4. **秘密文件排除表** 补上 `.npmrc`、`.netrc`、`.pgpass`、`*.pfx`、`*.keystore`。测试：`FileRestorerTest.files_that_look_like_secrets_are_not_restored_and_the_user_is_told`（加了这 5 个样例）。

新增公开成员：core `SessionTree.RESTORED_FILES_TAG`、`SessionTree.restoredFiles(Message)`。主控记录、不修的：Linux 上带冒号的符号链接对冒号后缀规则的理论绕过；`--resume` 后 `staleUsage` 为空；压缩后到下一次回复之前，ctx% 显示的是旧值。

## 附录
**新增或变更的公开成员**
- core 新增：`Tool.effect(JsonNode)`、`Tool.concurrencySafe(JsonNode)`、`Tool.effectOf(Tool, JsonNode)`；`TerminationReasonEnum`；`ContextStage`（`name/apply/kind/summaryOf/requestSent`）；`AgentEvent.AgentEnd.reason`、`AgentEvent.TurnStart.estimatedTokens`；`AgentConfig.stages/withStages`；`CompactingHooks.stages()/withHost(Consumer, BiFunction)/summary(List)/MAX_FAILURES`；`ModelSummarizerImpl(Supplier, ToolRegistry)`。
- core 删除：`CompactingHooks.withListener`；`CompactingHooks` 的 `transformContext/prepareRequest`（它不再是 `AgentHooks`）；`ModelSummarizerImpl(Supplier)`；`AgentConfig` 的 6 参构造器；`LoopSnapshot` 的 5 参和 7 参构造器。
- ext-hitl：`ApprovalDecisionEnum.ALLOW_SAVE`、`Approver.confirm(String)`（默认 false）。
- tui：`TerminalInteractionImpl.ask` 先精确匹配（行为变化）。

**P3C**：reporting 模式，PMD 6.21.0 + p3c-pmd 2.1.1，excludes 没有增加。Priority 2 共 9 条，全部是 M6 已有的嵌套 record 误报，本阶段没有新增。

**行数**（非空行 / 去掉注释后，主代码与测试）
| 模块 | main | test | main 去注释 | test 去注释 |
|---|---|---|---|---|
| model | 2663 | 1184 | 1925 | 1161 |
| core | 4992 | 3334 | 3280 | 3298 |
| tools | 804 | 420 | 708 | 412 |
| tui | 1005 | 355 | 764 | 352 |
| cli | 1911 | 1913 | 1645 | 1880 |
| ext-mcp | 2174 | 1577 | 1669 | 1535 |
| ext-hitl | 1400 | 969 | 1128 | 966 |
| ext-memory | 1187 | 609 | 1047 | 607 |
| ext-tasks | 384 | 123 | 310 | 122 |
| ext-verify | 628 | 355 | 498 | 353 |

**测试**：共 759 个，分布为 model 133、core 140、tools 119、tui 20、ext-mcp 52、ext-hitl 130、ext-memory 57、ext-tasks 5、ext-verify 18、cli 85（含 1 个 skipped 的 `BenchTest`）。

**live 脚本与日志**：都在 `D:\PaiCLI\tmp\m7live\`。
- `run.ps1` 从 `pai-next\.env` 读 key，只放进进程环境，不打印。每次运行结束后统计日志里 key 形态的字符串，结果都是 0。
- 日志：`plan.log`、`perm1.log`、`perm2.log`、`k8.log`、`k8b.log`、`bench.log`。
- 输入：`*-input.txt`。
- fixture 目录：`plan-proj`、`perm-proj`、`k8-proj`。
