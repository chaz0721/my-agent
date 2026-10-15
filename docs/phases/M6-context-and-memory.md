# M6 规格书：上下文与记忆（微压缩、结构化摘要、记忆文件形态）

> 前置：M5 通过（tag M5）。本阶段不新增内核概念；全部改动在 `pai-core.compaction`、`pai-ext-memory`，加一个很小的 core 钩子点。
> 分量：预计 ≤ 600 行主代码。四个子项按顺序做，前两项收益最大、风险最小；6C/6D 若时间紧可放 M6.5。

## 来自 M5 的输入（已裁决）
- 跨扩展共享 Guard/只读白名单：**本阶段不做**（M7 候选：Guard 下沉 core `policy` 包）。
- `ExtensionContext.tools()` 列表：本阶段不做。
- 已知热点 `SessionEntriesImpl.latest`/`append` 全量读文件：6D 顺手加按文件大小/mtime 的缓存。

## 6A. 微压缩（确定性，零模型调用）

目标：全量摘要触发前，先把**早期**的大块工具结果替换为占位，保留调用与结构。预期把全量压缩频率降一个量级。

```java
package dev.pai.core.compaction;
public record MicroCompactionPolicy(int keepRecentTurns /*默认 4*/, int maxToolResultChars /*默认 2000*/, Set<String> neverTrim /*默认空*/) {}
public final class MicroCompactor {   // final class，非 SPI
  /** 对 keepRecentTurns 之前的 ToolResultMessage，content 超 maxToolResultChars 的替换为占位文本；返回新列表，未改动时返回同一实例。 */
  public List<Message> apply(List<Message> messages, MicroCompactionPolicy p);
}
```
- 占位文本固定格式：`[tool result trimmed by pai: <toolName>, <N> chars, <M> lines; call id <id>]`，保留 `toolCallId/toolName/isError`，Image 块一律替换。
- 触发：`CompactingHooks.transformContext` 中，估算超过 `thresholdFor(model) × 0.6` 时先做微压缩；仍超阈值才走全量摘要。微压缩**不写** session entry（它是投影，每次请求重算，结果确定，`buildContext` 不受影响）。
- 微压缩后的消息列表变化会触发 `ContextCompacted`——为区分，`AgentEvent.ContextCompacted` 加 `kind` 分量（`MICRO|SUMMARY`，保留旧构造器默认 SUMMARY）。TUI 微压缩不打印整行，只在状态行 ctx% 体现。
- 旧仓库 `context/compaction/DeterministicMicroCompactor.java` 可读后重写（哪些工具、多旧、占位格式），不复制。
- 测试：早期大结果被替换、最近 N 轮不动、ToolCall/ToolResult 配对不变、幂等（二次 apply 返回同一实例）、Image 块替换。

## 6B. 结构化摘要模板

目标：摘要按固定槽位组织，压缩后模型不必重读文件。

- `ModelSummarizerImpl` 的提示词改为资源文件 `summary-prompt.md`，要求输出以下节（英文标题固定）：`## Task`、`## Done`、`## Files touched`（路径列表，每项一行，带一句改了什么）、`## Decisions`（决定 + 理由）、`## Open issues`、`## Next step`。
- `SummaryValidator`（package-private）：缺节、`Files touched` 为空但本段消息里有 write/edit 调用、总长 > 预算 → 判为不合格，重试一次（更短的提示），仍不合格 → 不压缩、warn、继续（与现有"摘要失败不压缩"一致）。思路来自旧 `SessionDigestValidator`，只搬"校验字段否则不提交"的思想。
- 二次压缩时旧摘要作为输入的一部分，模板要求"合并而非追加"（Files touched 去重）。
- 测试：FakeSummarizer 产缺节输出 → 不压缩；合格输出 → CompactionEntry.summary 含六节；`Files touched` 含本段 edit 的路径。

## 6C. 压缩 → 记忆候选

目标：压缩时模型已在判断"什么值得留"，`## Decisions` 与用户纠正是记忆的最佳来源。

- `CompactingHooks` 在成功写入 `CompactionEntry` 后，发出 `AgentEvent.ContextCompacted(kind=SUMMARY, summary)`（`summary` 为新增 Optional 分量）。
- `pai-ext-memory` 监听该事件，从 `## Decisions` 节抽取候选（每行一条，≤ 200 字，过 SecretFilter），缓存在会话内；会话结束（`AgentEnd` 后 REPL 退出前，或 `/memory review`）时 `ui().confirm("N memory candidates from this session, review?")` → 逐条 `y/n/e(edit)`；无 ui 不保存，只 notify 数量。
- 不做无确认的自动写入。

## 6D. 记忆文件形态对齐 Claude Code

目标：用户能用编辑器直接改；注入稳定不破 cache。

- 存储改为 `~/.pai/memory/<scope>/MEMORY.md`（索引，每条一行 `- [id] text #tag`）+ 可选主题文件 `<scope>/<topic>.md`；scope = `global` | `<cwd-hash>`（目录下放一个 `path.txt` 记录原始路径便于人找）。旧 JSONL 一次性迁移（启动时发现旧文件 → 转换 → 重命名为 `.migrated`）。
- 注入：会话开始时固定注入 `MEMORY.md` 前 N 行（默认 60 行 / 4000 字，project 在前 global 在后），整个会话不变；主题文件只经 `recall` 工具按需读（`recall(topic)` 或 `recall(query)` 子串匹配）。
- `remember(text, scope, topic?)`：无 topic 写入 MEMORY.md；有 topic 追加到主题文件并在 MEMORY.md 加一行索引。
- `/memory list|search|delete|clear|open|review`；`open` 打印文件路径。
- 删除：MEMORY.md 直接删行（文件是给人编辑的，不再软删）。
- `SessionEntriesImpl` 的 `latest`/`append` 加缓存（文件 size+mtime 不变则复用解析结果）。
- 测试：迁移幂等；注入行数/字数上限；project 优先于 global；主题文件按需；编辑器手改后 `/memory list` 读到。

## 不做
embedding/向量、RRF、跨机同步、图结构、自动抽取（无确认）、按查询动态注入。

## 验收
- [ ] 8k 窗口下同一任务：全量压缩次数相比 M5（3 次）下降（预期 ≤1 次），且模型仍答对读过哪些文件/项目名。
- [ ] 压缩后的 `CompactionEntry.summary` 含六节，`Files touched` 列出本段 edit 的路径；压缩后模型未重读这些文件即可继续改。
- [ ] 一次会话结束时弹出候选确认，`y` 后出现在 MEMORY.md；`n` 不写。
- [ ] 手动编辑 MEMORY.md 后，新会话 system prompt 含改后的内容（PAI_DEBUG_HTTP=1）；整个会话 system prompt 的 memory 段不变。
- [ ] 旧 JSONL 记忆迁移成功，`.migrated` 留档。
- [ ] core 改动仅限 `AgentEvent.ContextCompacted` 新分量与 compaction 包；报告 `docs/reports/M6-report.md`。
