# M6 实施报告

- 分支 / 合入 commit：`impl/M6`（8 个 commit，`fd79d7d..cbb5e9f`，基于 `main` + docs commit）；未 push、未合入
- CI 链接：无 remote，只在本机跑了 `mvn -B -ntp verify`：BUILD SUCCESS，641 个测试
- 实施 session 模型与日期：Claude Opus 5.5，2026-10-15

## 1. 验收清单

- [x] **8k 窗口同一任务，全量压缩次数下降**。PAI_HOME 下 `models.json` 把 deepseek-chat 的 window 设为 8000，阈值 4000。任务是 6 条输入：read README、read notes、`dir`、再次 read README、edit Main.java、不读文件回答问题。fixture 按 M2 的量级（README 2.1KB、notes 2.7KB），M5 jar 与 M6 jar 各跑一次：
  - **M5：4 次**（`compacted 4 of 11 / 5 of 12 / 5 of 12 / 4 of 10`）
  - **M6：0 次**（`m6-4.log` 里 `compacted` 出现 0 次）。请求体里出现占位 `[tool result trimmed by pai: read, 2827 chars, 28 lines; call id call_00_zo0E…]` 5 次、`read, 2188 chars, 18 lines` 1 次。
  - 回答：`Files I've touched: README.md — read (twice) / notes.txt — read / Main.java — read and edited … Project name: Harbor Lights.`，读过的文件和项目名都对。
  - 代价：我额外问的 codename 答不出（"those results were trimmed"），notes.txt 的结果已被占位替换。
  - 补充：用大文件 fixture（8.3/9.1KB）跑，M5 是 8 次；第一版 M6（只有默认档）是 7 次，见 §6-2。
- [~] **`CompactionEntry.summary` 含六节，`Files touched` 列出 edit 的路径**：通过。**"压缩后不重读即可继续改"：未通过。**
  - live（`home-cont`）：`/compact` 之后的 entry 是 `## Task … ## Done … ## Files touched\n- \`Main.java\`: read, then edited line 3 to print "lighthouse" instead of "harbor". ## Decisions … ## Open issues\n- none ## Next step`。
  - 下一条"把 lighthouse 改成 beacon"时，模型仍先 `read Main.java` 再 `edit`。原因：base system prompt 写着"Read a file before you change it"，而 edit 需要逐字的 oldText，摘要里没有原文。
  - 单测覆盖：缺节两次 → 不压缩；摘要漏写 edit 的路径 → 补上 `- src/Main.java: changed`。
- [x] **会话结束弹出候选确认，`y` 写入，`n` 不写**（`home-cand2`，先 `/compact` 再 `/quit`）。交互是：
  ```
  3 memory candidates from this session, review?
  Remember: Keep the schedule renderer in a single `Main` class: the project is tiny.   → y
  Remember: Use JUnit 5 for tests: it is the standard.                                   → n
  Remember: These two decisions are pending explicit one-line confirmation …             → y
  ! memory: saved 2 of 3 candidates
  ```
  MEMORY.md 里有第 1、3 条，没有 JUnit 那条。另一次会话的输入里没有答复（读到 EOF），结果是 `saved 0 of 1`。
- [x] **手动编辑 MEMORY.md 后，新会话的 system prompt 含改后的内容；整个会话的 memory 段不变**（PAI_DEBUG_HTTP=1）。
  - 会话 1 里调用了一次 `remember`，4 个请求的 memory 段只有一种：`[global]\n- [a1b2c3d4] The user prefers short answers #style`。
  - 用 `Add-Content` 追加一行 `- [handedit] The user's favourite editor is Helix #tools` 后开会话 2，请求体里是 `<memory>…[project]\n- [c92d9f51] The demo build command is make demo #build\n[global]\n- [a1b2c3d4] …\n- [handedit] The user's favourite editor is Helix #tools\n</memory>`，`/memory list` 也读到了。
  - 日志里没有 Authorization 头，key 形态字符串 0 个。
- [x] **旧 JSONL 迁移成功，`.migrated` 留档**。启动时提示 `! memory: moved 1 memories from the old JSONL files to MEMORY.md (old files kept as .migrated)`；目录里有 `global.jsonl.migrated`、`global/MEMORY.md`、`<hash>/MEMORY.md`、`<hash>/path.txt`。已删除的条目（deletedAt）没有迁移。单测：迁移第二次返回 0、文件不变；把旧文件拷回去再跑 → `.migrated2`，不重复写入。
- [x] **core 改动只在 `AgentEvent.ContextCompacted` 与 compaction 包**。另有 cli（接线、ctx% 改用投影估算、`SessionEntriesImpl` 缓存）和 tui `EventView`（MICRO 不打印）。

## 2. `build/check-budgets.sh`
model 2663 · core 4642/5000 · cli 1715/2000 · tui 999/1500 · ext-hitl 1086 · ext-mcp 2166 · ext-memory 1063 · ext-tasks 380 · ext-verify 628 · budgets OK。最大文件 `CompactingHooks.java` 249 行；TODO 0。

## 3. 核心改动逐文件
- `AgentEvent.ContextCompacted` 新增分量 `kind`（`KindEnum{MICRO,SUMMARY}`）和 `Optional<String> summary`，保留 2 参构造器（默认 SUMMARY、无摘要）。
- compaction 包：
  - 新增 `MicroCompactionPolicy`、`MicroCompactor`（final，非 SPI）、`SummaryValidator`（包私有）、`summary-prompt.md`、`summary-retry.md`；删除 `summarize.md`。
  - `CompactingHooks` 新增 `withListener(Consumer<AgentEvent>)`、`projected(List, ModelRef)`、常量 `MICRO_TRIGGER_PERCENT`。
  - `ModelSummarizerImpl` 改为校验后重试一次，新增常量 `MAX_SUMMARY_CHARS`。
- 以上新增的公开成员需要主控登记。

## 4. 签名异议
无。规格签名都按原样实现。

## 5. SPI 漏洞
1. **loop 不知道压缩的种类**。`AgentLoop` 只要 transformContext 返回的列表变了，就发 2 参 `ContextCompacted`，钩子没法告诉它 kind 和 summary。现状是一次 loop 内的摘要会产生两个 SUMMARY 事件：loop 发一个不带摘要的（给 renderer、Repl、扩展），钩子经 listener 再发一个带摘要的（只给扩展）。建议 M7 让钩子能声明压缩结果，或改由压缩钩子独家发这个事件。
2. **`UserInteraction` 不能输入自由文本**。规格里的 `e(edit)` 做不了"当场改写"，现在 `e` = 保存并提示去文件里改（`; reword them in <path>`）。
3. **扩展读不到 `CompactionEntry`**（`latestEntry` 只查 CustomEntry），所以摘要只能靠事件拿到。另外，"`SessionEnded` 时终端仍可交互"依赖 cli 在关闭 renderer 之前 dispatch，这个顺序没写进合同。

## 6. 偏离与理由
1. **微压缩在 `prepareRequest` 里套用，`transformContext` 只做判断**。loop 会把 transformContext 的返回值存成自己的上下文，并且一变就发 SUMMARY 事件。如果在 transformContext 里做微压缩，会有三个后果：TUI 打印整行；Repl 把 leaf 重置为 store 最新的 entry（fork 后会跳到别的分支）；投影与 session 的逐条 equals 对齐失败，之后的全量摘要全部被跳过。放进 `prepareRequest` 后，只改本次请求，与"投影、不写 session"一致。
2. **加了第二档**（`keepRecentTurns=1`，其余同默认）：默认档投影仍超阈值时用它，再超才做摘要。8k 窗口下 system prompt 加工具定义约占 2.7k token，默认保留 4 轮时每个大结果都在保留范围内，微压缩起不了作用。
3. **投影大小按"原估算 − 被替换部分的节省量"计算**。`CharsTokenEstimatorImpl` 用上一条回复的 usage 校准（`max(按字符, usage+后续)`），而那条 usage 是按未裁剪的请求统计的。直接估算投影会被它抬高，第一次 live 只从 4 次降到 3 次就是这个原因。
4. `SummaryValidator.normalize` 合格后去重 `Files touched`，并补上本段 write/edit 过但摘要漏写的路径（规格只要求校验；为验收 2 的确定性多做了这一步）。
5. `SessionEntriesImpl` 在 cli 里，不在 core。缓存键是 (session, leaf) 而不是 size+mtime：`SessionStore` 不暴露文件路径，而 entry 只追加、不改写，同一 leaf 的路径不会变。Repl 的侧栏改为复用同一个实例。
6. `recall(query)` 沿用 BM25（内含整串匹配加分），不是纯子串匹配；topic 在 MEMORY.md 里只占一行索引（`[topic-<name>]`），不是每条一行；`/memory inject on|off` 保留。
7. `/compact` 现在也会产生带摘要的事件（原来不发任何事件），手动压缩同样会产生候选。

## 7. 坑
- `device_commit_files` 按 stagedPath 缓存：同一路径重新 stage 时，可能悄悄送上旧内容（MicroCompactor 修复一度没落盘；候选测试的输入是旧版，带着 `/memory review`）。现在每次同步都用唯一路径并核对 hash。
- `start_process` 的外层 shell 会吃掉 `-Command "…"` 里的 `$变量`，要写成 `.ps1` 用 `-File` 跑。PowerShell 重定向产生的日志是 UTF-16，stderr 按 120 列折行。
- memory 扩展是 drop-in jar，live 测试要拷到 `PAI_HOME/extensions/`，否则会报 `unknown command: /memory`。
- 估算校准问题见 `CharsTokenEstimatorImpl.estimate` 的 `calibrated` 分支；任何"投影后再估算"的逻辑都会踩到。

## 8. 建议（未做）
- 占位可以保留结果的头几行，或保留已被模型引用的行，避免像 codename 这样的事实丢失；`neverTrim` 默认可以加 `load_skill`。
- 候选里会混进元信息句（如"pending confirmation"），可以在模板里要求 Decisions 只写"决定: 理由"，或者加一个过滤。
- 微压缩的边界每轮都会后移，前缀缓存每轮失效一段；可以考虑按"每 N 轮推进一次"取整。

## 9. 给 M7 的输入
- "压缩后不重读即可改"要在 M7 的"压缩后文件恢复"里解决：要么摘要后把 `Files touched` 的当前内容注入回去，要么改 base prompt 的"先读再改"规则。
- §5-1 的事件双发请在 M7 的终止原因管线里一并定。
- SummaryValidator 的 write 工具名（`write`/`edit`）是硬编码的；M7 引入 `Tool.effect(input)` 后可以改为按 effect 判断。

## 附录
**P3C**：reporting 模式，PMD 6.21.0 + p3c-pmd 2.1.1，本阶段没有新增 excludes。Priority 2 新增 2 条，都是已知的嵌套 record 误报：`CompactingHooks.Target`、`MemoryCandidates.Review`。
**行数**（非空、非注释，主/测）：model 1892/1151，core 2961/2492，tools 480/334，tui 749/339，cli 1463/1484，ext-mcp 1641/1521，ext-hitl 862/732，ext-memory 935/476，ext-tasks 301/121，ext-verify 493/351。
**测试**：641 个 = model 133、core 108、tools 21、tui 19、ext-mcp 52、ext-hitl 162、ext-memory 50、ext-tasks 5、ext-verify 18、cli 73。
**live 脚本与日志**：`D:\PaiCLI\tmp\m6live\`（`run.ps1` 从 `.env` 读 key 只进进程环境、不打印；`m5-3.log`、`m6-4.log`、`mem1/2.log`、`cand2.log`、`cont.log`）。

## 10. 审查修复（主控裁决"修后合入"）
| # | 修法 | 测试 |
|---|---|---|
| P1-1 | `prepareRequest` 记住本次实际发出时被裁的 toolCallId 集合（`sentTrimmed`）。估算投影时，先直接 `est.estimate(view)`（上一条 usage 已是裁剪后的大小）；只对"本次新裁、上次未裁"的结果扣节省量，并以"去掉 usage 校准后按字符估算的 view"为下限。这样不会重复扣，也保留了 M2 的 usage 校准。两档判断与 `compact` 都改用这个估算。 | `CompactingHooksMicroTest.after_trimming_the_reported_size_is_trusted_and_still_triggers_a_summary`：第一次请求裁掉 r1/r2；第二次的 usage 取裁剪后请求的实际大小（大于字符估算），断言 summarizer 被调用 1 次、上下文被压缩。旧的 `the_reported_size_of_the_last_reply_does_not_hide_what_trimming_saves` 仍然通过 |
| P1-2 | `MemoryCandidates.key` 做归一化（trim、折叠空白、去尾标点、小写）后取哈希。`offer`/`review` 跳过三类候选：已在 `store.all()` 中的、本会话已 pending 的、被拒的。`n` 或整体拒绝 review 会把 key 记入会话集合，并写一条 `CustomEntry{customType:"memory-rejected", data:{keys:[…]}}`；`SessionStarted`（含 resume）时从 `latestEntry` 读回 | `MemoryCandidatesTest.a_second_compaction_that_carries_the_old_decisions_asks_nothing_again`（`/memory review` 后再压缩两次、带回旧 Decisions，不再提示，MEMORY.md 无重复行）、`after_a_resume_a_rejected_candidate_is_not_offered_again`、`keys_ignore_case_blanks_and_trailing_punctuation` |
| P2-1 | `SummaryValidator.canonical`：`#`～`######`、`**X**`、大小写、尾冒号、首尾空白都改写为规范标题，之后再校验，写入 entry 的是规范形式。摘要失败后，自动压缩暂停 `RETRY_AFTER_TURNS=3` 个 turn（`force` 即 `/compact` 与 overflow 不受限） | `SummaryValidatorTest.loose_headings_are_made_canonical`、`CompactingHooksMicroTest.a_failed_summary_pauses_automatic_compaction_for_three_turns` |
| P2-2 | `Files touched` = 摘要所列 ∪ 上一份摘要的 Files touched ∪ 本段成功的 write/edit 路径。"已列出"的判断用规范化完整路径（`\`→`/`、去 `./`、忽略大小写，相等或以 `/x` 结尾），不再用文件名子串。`writtenPaths` 排除 `isError` 结果对应的调用 | `the_file_list_keeps_earlier_files_and_matches_full_paths`、`failed_writes_are_not_files_touched` |
| P2-3 | 第二档滞回：一旦用到第二档，就一直保持到下一次成功摘要（`tight` 标志；`projected()` 只读这个标志、不置位，cli 状态行不会改变它） | `when_the_default_projection_is_still_too_big_only_the_latest_turn_keeps_its_results`（同一 hooks 保持第二档；新 hooks 用默认档） |
| P2-4 | `MemoryFile`：临时文件名带 pid 和 nanoTime；先严格解码，失败时 warn 一次 "MEMORY.md is not UTF-8" 再按 REPLACE 读；跳过 BOM | `MemoryStoreTest.files_with_a_bom_or_bytes_that_are_not_utf8_are_still_read`、`writes_leave_no_temporary_files_and_cuts_keep_surrogate_pairs` |
| P2-5 | topic 黑名单：`memory`、con/prn/aux/nul/com1-9/lpt1-9（不区分大小写） | `MemoryStoreTest.a_topic_goes_to_its_own_file_with_one_index_line`（新增断言）、`MemoryExtensionTest.tags_are_limited_and_checked_for_secrets`（topic "memory" 报错） |
| P2-6 | tag 必须匹配 `[^\s#]+`，不合格就报错 | `tags_are_limited_and_checked_for_secrets`（`two words`、`a#b`） |
| P2-7 | `MemoryFile.cut` 按 code point 截断（`offsetByCodePoints`），候选、topic 索引摘要、`recall(topic)` 都改用它 | `keys_ignore_case_blanks_and_trailing_punctuation`（300 个 emoji → 200 个 code point，不切开代理对）、`writes_leave_no_…_surrogate_pairs` |
| P2-8 | MEMORY.md 文件头改为 3 行注释：格式、无 id 行按文本哈希派生 id、`/memory clear` 删除全部 bullet 行 | `add_list_and_delete_edit_the_markdown_files`（头部 3 行都是注释） |
| P2-9 | `CompactingHooks` 类 Javadoc 写明：必须排在 host hooks 之后注册；更早注册的钩子在 `prepareRequest` 里看到的是裁剪前的消息；另外写了滞回与暂停 | — |

**8k live 重跑**（修复后的 jar，同一任务与 fixture，`m6r.log`）：**全量压缩 0 次**（M5 为 4 次）。最后的回答是 `Files touched: README.md — read (twice) / notes.txt — read / Main.java — read and edited … Project name: Harbor Lights / Release codename: NIGHTJAR`；这次 codename 也答对了，因为模型在第 2 轮的回复里自己写出了它。

测试总数 641 → 646（core 108→113，ext-memory 50→55），`mvn -B -ntp verify` BUILD SUCCESS。
