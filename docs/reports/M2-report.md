# M2 实施报告

- 分支 / commit：`impl/M2`（D:\PaiCLI\pai-next），基于 main `bb0f9c4`；`0ab875a` model、`1c57d4c` core、`3e41a0e` tui、`10b82fd` core 修复、`8c9af21` cli、`03c03c4` tui 修复，加上本报告。未合入 main。
- CI：**无**（仍然没有 remote）。本地 Windows `mvn -B -ntp verify` 结果 BUILD SUCCESS，共 212 个测试：model 97 / core 61 / tools 17 / tui 9 / cli 28。
- 实施 session：Claude Opus 5.5，2026-10-15。live 测试用 DeepSeek（deepseek-chat / deepseek-reasoner）。Anthropic key 不可用，"若 key 到位"那一条已跳过。

## 1. 验收清单

- [x] **自动压缩**：`~/.pai/models.json` 里把 deepseek-chat 的 `contextWindow` 设为 8000（阈值 4000 token）。4 轮对话里有 read/read/bash 调用，过程中触发了 3 次压缩（`compacted 3 of 9 messages (5785 tokens estimated)`）。压缩后模型仍能正确回答"读过哪些文件、项目名是什么"。`/tree` 里能看到 `compaction` 节点，见附录 A。
- [~] **/model 切换**：chat → reasoner → anthropic → chat，`PAI_DEBUG_HTTP=1`（附录 B）。历史里带 tool_use 时，两个 OpenAI 端请求都被接受。Anthropic 端请求体的结构是对的：tool_use 与 tool_result 按 id 成对，DeepSeek 的 reasoning 没有跨 provider 回放。但用的是占位 key，返回 401，**未经 API 实际接受，待 key 到位后补测**。会话里写入了 `model_change` entry，切换后的回复带 `modelId` 字段。
- [x] **skill**：`.pai/skills/demo/SKILL.md` 生效。system prompt 中出现 `# Skills` 索引，模型调用了 `load_skill demo`，并按 skill 的要求回复（附录 C）。同一个请求里还带着 `PAI.md` 生成的 `<project-context>`，模型据此答出代号 BLUEBIRD。
- [x] **/fork**：`--resume` 后 `/fork 8d9b53`（第一轮回复）再继续对话。新分支只知道 README（"that's the only file I've read"）。JSONL 中新 user 消息的 `parentId=8d9b5321`，旧分支 17 行原样保留。`/tree` 两个分支都能看到（附录 D）。
- [x] **管道 / Ctrl+C / 缩放**：
  - 管道：`echo hi| java -jar pai-cli.jar ...` 走 Plain 渲染，exit=0。
  - Ctrl+C 与缩放：在真实 Windows 控制台里跑 JLine 模式，用 `WriteConsoleInput` 注入按键。流式输出中途发 `GenerateConsoleCtrlEvent` 后，该轮 assistant 被记为 `stopReason=ABORTED error=cancelled`，REPL 继续可用。随后把窗口改为 70 列，再发 "pong"，正常回复；`/tree` 和 `/quit` 也正常（附录 E）。
  - 自动化测试另覆盖了 xterm 仿真下的 WINCH 和 dumb 终端两种情况（`JLineRendererImplTest`）。
- [x] **pai-core 主代码**：2249 行（去空行与注释）/ 3528 行（预算脚本口径），≤ 4000。
- [x] 各类 grep 均为 0：TODO/FIXME/XXX、核心层 CJK、核心层 System.out/err。
- [x] 所有 live 输出、日志和 `PAI_HOME` 下的文件都扫描过，含 key 的文件数为 0。

## 2. `build/check-budgets.sh`
```
pai-model LOC=2327 (max 4000)
pai-core LOC=3528 (max 5000)
pai-cli LOC=947 (max 1500)
budgets OK
```

## 3. `git diff --stat main..HEAD`
共 66 个文件，+4144/−343。核心主代码 28 个文件，+1508/−114：
- 新增包 `compaction`、`skill`。
- `SessionTree`：buildContext 的投影逻辑抽到这里复用，供压缩定位 firstKept 和 /tree 使用。
- `JsonlSessionStoreImpl`：append 加 id/leaf 缓存。
- `AgentLoop`：发出 ContextCompacted、overflow 重试一次、调用 prepareRequest。
- `AgentHooks` / `HooksChain` / `ExtensionHost`：新增 prepareRequest。
- `prompt.ProjectContext`：加载 PAI.md。
- model：结构化的 toolCallDiagnostics。

docs 只改了 `adr/0003` 末尾的"修订记录"。

## 4. 签名异议 / 新增公开成员（只增不改）
1. `AssistantMessage` 新增第 8 个分量 `Optional<Map<String,String>> toolCallDiagnostics`，保留原 7 参构造器。`toolCallDiagnostic(id)` 改为读这个 map，`errorMessage` 只用于错误。M1 写的 JSONL 仍能解析（有测试）。**需要主控记入 DECISIONS-LOG**，本 session 没有改那个文件。
2. `AgentConfig` 新增分量 `retryOnceOnContextOverflow`，保留原 5 参构造器（默认 true），另加 `withModel`。
3. `LoopState` 新增两个 default 方法：`contextOverflow()` 和 `cancel()`。前者让压缩钩子知道要强制压缩，后者让慢的摘要可取消。ADR 0003 修订记录已写明。
4. `CommandContext.sendUserMessage(String)`（抽象方法）。ADR 0005 写了"ctx 可发送 user message"，但 M1 漏了。`/skill:name` 和 `/init` 都需要它。
5. 其余新增：
   - `Renderer` 多了 `onInterrupt(Runnable)`。原规格合同里没有 Ctrl+C 接口，而 JLine 要求信号经它的 handler 走。
   - `ToolCallSummaries.describe/outcome`
   - `CompactingHooks.compact(msgs, state, force)`，供 `/compact` 使用
   - `CompactionPolicy.splitIndex/defaults` 和三个默认常量
   - 新公开类型 `SessionTree`（含 `Projection`）
   - `SkillRegistry.body/roots`
   - `SessionTreeView`
   - `StatusLine.withPhase/render`
6. 复审后定稿：`CompactingHooks` 的公开构造器是 `(TokenEstimator, CompactionPolicy, Summarizer, SessionStore, Target)`，共 5 个参数；新增公开 record `CompactingHooks.Target(Supplier<SessionId> session, Supplier<Optional<String>> leafId)`。leaf 由调用方提供，对齐检查改为逐条 equals。新增的 fixture 往返测试证明：真实解码出的 AssistantMessage 经 JSONL 往返后仍然 equals，覆盖了 15 个 fixture。
7. 原异议（已由第 6 条的 Target 解决）：`CompactingHooks` 只拿得到 messages，要写 `firstKeptEntryId` 就必须把 messages 映射回 entry id。现在的做法依赖一个前提：上下文就是 store 最后一条 entry 的投影（cli 在每次请求前都已落盘）；对不上就跳过并记 warn。建议 M3 让 `LoopState` 提供 `leafEntryId()`，或者把压缩的落盘交给 host。

## 5. SPI 漏洞
- `ExtensionContext` 拿不到 cwd，所以 `SkillsExtension` 只能由 cli 构造后传给 `install`，不走 ServiceLoader。
- 见 4.4。

## 6. 偏离与理由
- **JLine terminal provider 选 JNI**：JLine 4 已经删除了 jansi/jna provider（这两个只到 3.30.x）；ffm provider 要 JDK 22。JDK 21 上 JNI 需要 `java --enable-native-access=ALL-UNNAMED -jar pai-cli.jar`；manifest 的 `Enable-Native-Access` 只对 22+ 生效。没加这个参数，或 stdout/stderr 被重定向时，`RendererFactory` 会打一行提示并回落到 Plain，不会退成 dumb 终端。
- 工具行：spec 要求同一行从 `▸` 变成 `✓`。实际做法是运行中的调用显示在 JLine `Status` 区，完成后在 transcript 打 `✓ …` 一行。这样不手写光标移动，并行调用也不会互相覆盖。
- 摘要 UserMessage 仍沿用 M1 的 `SUMMARY_PREFIX + summary`，不加 `<summary>` 标签，保证与 `buildContext` 严格等价。
- 没有内置的 resources skills，SkillRegistry 的根只有 `~/.pai/skills` 和 `<cwd>/.pai/skills`。
- cli 的 `EventPrinter`、`Interrupts` 已迁到 tui（PlainRendererImpl）。PMD excludes 增加 12 个，删除 1 个，共 50 个。
- GLOSSARY 里没有 "project context / PAI.md" 这个词，请主控补。

## 7. 坑
- JLine 4 JNI：报 `Native access is not enabled for the current module`，见 `JniTerminalProvider.checkNativeAccess`。
- 默认 `signalHandler=SIG_DFL` 时，JLine 会把 INT 注册成 JVM 默认处理（直接退出），`terminal.handle(INT)` 永远不会被调用。必须设成 `SIG_IGN` 这类非默认值（`JLineRendererImpl.system`）。
- JLine 的 writer 用 `outputEncoding()`（即 stdout 编码，Windows 上是 GBK）编码，不是 `encoding()`。符号的退化判断必须看前者。
- 压缩替换 1 条、保留 6 条时，消息数不变。所以 ContextCompacted 改为"数量或元素引用不同"即触发（`AgentLoop.changed`）。REPL 靠这个事件刷新 leaf，漏发会让后续消息挂到压缩 entry 的兄弟位置上。
- Windows 测试中，PrintStream.println 输出 CRLF；AssertJ 的 `doesNotContainAnyElementsOf(空集)` 会直接抛异常。
- `device_stage_files` 最多支持 7 层目录，回拉 spotless 格式化后的文件时需要先扁平复制到 `target\pull`。
- `/fork` 之后还没发新消息就执行 `/compact`：此时 store 的 leaf 与 REPL 的 leaf 不一致，压缩会安全跳过。

## 8. 建议（未做）
- 提供 `pai.cmd` / `pai` 启动脚本，内置 `--enable-native-access=ALL-UNNAMED`。
- `--resume` 时从分支上最近的 `ModelChangeEntry` 恢复模型。
- `LoopState.leafEntryId()`（见 4.6）。
- Anthropic key 到位后：录 fixtures、跑 M1 的两项 live，再补 /model 往返。
- 代码块围栏着色；`/skills reload`；`check-budgets.sh` 把 pai-tui 纳入；接 GitHub remote 跑 CI。

## 9. 给 M3 的输入
- 扩展要用 `prepareRequest` 改 system prompt 时，参考 `SkillsExtension` 的做法：`SystemPromptBuilder` 加 base + section。
- ExtensionContext 需要考虑暴露 cwd 和只读的 session 视图（见第 5 节）。
- MCP 工具的展示：在 `ToolCallSummaries` 的 map 里加一条，或者用通用格式。

## 附录
**P3C**：reporting 模式；maven-pmd-plugin 3.13.0，PMD 6.21.0，p3c-pmd 2.1.1；excludes 50 个，处理错误 0。剩余 4 条 Priority 2 的 `LowerCamelCaseVariableNamingRule`，都是 M1 已知的误报（嵌套 record）。M2 新增的 `useJLine` 违规已经改名修掉。

**行数**（去空行与注释，主 / 测试）：model 1659 / 970；core 2249 / 1454；tools 428 / 286；tui 595 / 221；cli 828 / 475。

**A. 压缩**（窗口 8000，Plain/GBK）
```
> [context compacted: 9 -> 7 messages]
> $ ls  ...  ok $ ls (exit 0, 0.60s)
> [context compacted: 10 -> 7 messages]
Files read so far: `README.md` and `notes.txt` ... Project name: **Harbor Lights**
* 49580a user: Use bash to run: ls
* c7a4ed compaction (5785 tokens, kept from 8d9b53): ## Goal The user asked for ...
* 911363 compaction (5985 tokens, kept from d71ba6): ## Goal ...
* 936517 compaction (6067 tokens, kept from 0b4afe): ## Goal ...
* e792b5 assistant: Files read so far: ...  <- leaf
```
**A2. 复审修复后重跑**（DeepSeek，窗口 8000，逐条 equals 对齐；stderr 中 WARN 共 0 条）
```
[context compacted: 9 -> 7 messages]
[context compacted: 9 -> 8 messages]
[context compacted: 10 -> 7 messages]
Files read so far: `README.md` and `notes.txt` (plus `ls` listed `PAI.md`, which we haven't read). Project name: **Harbor Lights**
* f2544e compaction (5792 tokens, kept from 7f424a) / 6bc5ee compaction (6029 tokens, kept from 0261f4) / 388802 compaction (6118 tokens, kept from ca4045)
[main] INFO dev.pai.core.compaction.CompactingHooks - compacted 3 of 9 messages (5792 tokens estimated)   (x3, no warn)
```
**B. /model 请求体摘要**（bodies.ps1 解析 debug 日志）
```
request 4 -> api.deepseek.com model=deepseek-reasoner  ... assistant tool_calls=call_00_s96Y…:bash reasoning_content=yes / tool tool_call_id=call_00_s96Y…
request 5 -> api.anthropic.com/v1/messages model=claude-sonnet-4-5 tools=5
   user [text] / assistant [tool_use:call_00_smYJ…] / user [tool_result:call_00_smYJ…] / assistant [text] / user [text]
   assistant [tool_use:call_00_s96Y…] / user [tool_result:call_00_s96Y…] / assistant [text] / user [text]   → HTTP 401 invalid x-api-key
request 6 -> api.deepseek.com model=deepseek-chat ... (含 reasoner 的两条 assistant) → 200，答出两次 echo 输出
```
**C. skill + PAI.md**
```
> load_skill demo  ok load_skill demo (0.00s)
Greetings from the demo skill!
> BLUEBIRD.
system: ...# Skills\n\nSkills are task-specific instructions. When a task matches a skill, call load_skill ...\n- demo: Greets the user ...
        # Project context\n\n<project-context>\n<!-- ...\m2\PAI.md -->\n# Project rules\nThe project codename is BLUEBIRD...
```
**D. /fork 后的 JSONL**（只列新分支）
```
message 8d9b5321 parent=3b3e9f10 assistant      ← fork 点
message 5ede4ae1 parent=8d9b5321 user           ← 新分支（旧分支 d71ba675 同样挂在 8d9b5321 下）
message 88562a54 parent=5ede4ae1 assistant / cdc92617 tool_result / 4a3cad89 assistant / 0175e34c user / 408992a0 assistant
```
**E. 真实控制台（JLine）屏幕缓冲转储**
```
> Reply with the single word: pong
pong
* e2eb1e assistant: []          ← Ctrl+C 中断的回合（stopReason=ABORTED）
* ee7bfa assistant: pong  <- leaf
openai-compat/deepseek-chat | ctx 31% | in 5.4k out 132 | c72cc8a6 ...   ← Status 行（GBK 用 ASCII 分隔符）
```
