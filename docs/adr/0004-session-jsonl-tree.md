# ADR 0004：Session 是 JSONL 追加写的树，不是数据库

状态：已接受　日期：2026-10-14

## 背景

旧仓库用 SQLite 做 session 主存：9 版迁移脚本、`RuntimeRepository`/`RuntimeModelCallStore`/`RuntimeToolCallStore`（共 85KB）、租约、恢复状态机、"at-most-once 自动恢复"审计、`/resume` 需要 UNKNOWN 人工决策。对一个单进程单用户 CLI，这是复杂度负债，而且 session 不可读、不可 diff、迁移出错即不可用。

## 决定

一个 session = 一个 `.jsonl` 文件，位于 `~/.pai/sessions/<cwd-hash>/<timestamp>-<id>.jsonl`。

```
{"type":"session","version":1,"id":"...","cwd":"...","timestamp":"..."}         ← 第一行，header
{"type":"message","id":"a1","parentId":null,"timestamp":"...","message":{...}}   ← Message 的 JSON（ADR 0001 类型）
{"type":"message","id":"a2","parentId":"a1",...}
{"type":"model_change","id":"a3","parentId":"a2","providerId":"anthropic","modelId":"..."}
{"type":"compaction","id":"a4","parentId":"a3","summary":"...","firstKeptEntryId":"a2","tokensBefore":180000}
{"type":"label","id":"a5","parentId":"a4","targetId":"a2","label":"before refactor"}
```

```java
public sealed interface SessionEntry permits MessageEntry, ModelChangeEntry, CompactionEntry, LabelEntry, CustomEntry {
  String id(); Optional<String> parentId(); Instant timestamp();
}
public interface SessionStore {
  SessionId create(Path cwd);
  void append(SessionId id, SessionEntry entry);                 // 追加一行，fsync 可配置
  List<SessionEntry> readAll(SessionId id);
  List<Message> buildContext(SessionId id, String leafEntryId);  // 沿 parentId 链回溯 → 应用最近 compaction → 得到发给模型的 messages
  Optional<SessionId> mostRecent(Path cwd);
}
```

- **树**：`parentId` 形成树，分支/fork 不复制文件。`/tree` 和 fork 是 M2+ 的 UI 功能，但格式第一天就支持。
- **压缩**是一条 entry，不覆盖历史；`buildContext` 从最近的 compaction 开始投影。
- **扩展**可以写 `CustomEntry{customType, data}`，`buildContext` 默认忽略，扩展自己解释。
- 不做 WAL、不做崩溃恢复协议。进程崩溃最多丢最后一行不完整的 JSON，读取时跳过并告警。
- 需要检索/统计（如记忆、用量汇总）的扩展，自己在 JSONL 之上建索引（可以用 SQLite，但那是扩展的私事，不是 session 主存）。

## 后果

- `cat`、`grep`、`jq`、`git diff` 都能看 session；bug 报告直接附文件。
- 版本演进靠 header `version` + 读取时的迁移函数，不靠数据库迁移链。
- 放弃：exactly-once 工具副作用追踪、跨进程租约。接受——pi 和 Claude Code 都没有这些。
