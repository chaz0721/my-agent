# M1 实施报告

- 分支 / 合入 commit：`impl/M1`（D:\PaiCLI\pai-next），基于 `cf3ade5`；代码 commit `9be8069 baf7831 c508725 7f0b8d9 1234ca9 4d0afde` + 本报告。未合入 main。
- CI 链接：**无**。仓库没有 git remote，CI（verify + gitleaks）未跑；`build/ci/verify.yml.example` 仍需手动放到 `.github/workflows/`。本地 Windows `mvn -B -ntp verify` 全绿。
- 实施 session：Claude Opus 5.5，2026-10-15。

## 1. 验收清单

- [~] CI 绿：**未达成（无 remote）**。本地 `mvn -B -ntp verify` BUILD SUCCESS，121 个测试（model 56 / core 29 / tools 16 / cli 20），pom 无 `skipTests`。
- [x] fixtures：`fixtures/openai-compat/` 5 个**真实录制**（api.deepseek.com，deepseek-chat ×3、deepseek-reasoner ×2，含并行 tool_calls、reasoning_content、usage 末 chunk + `[DONE]`、finish_reason=length），`build/RecordFixtures.java` 录制、扫描 key 形态串后落盘；`fixtures/anthropic/` 4 个手写，头注 `handwritten, pending live recording`。每个文件头三行注释写日期/模型/场景。
- [x] ArchUnit：`pai-cli/.../ArchitectureTest` 15 条全过（00 §2 五条 + 01 §1.5 命名/可见性/禁词/Enum/Impl/静态字段/Date/System.out/中文字面量）。
- [~] Anthropic live 两项：**延后，待 key 到位**（主控补充 2）。Codec/Provider 已按规格实现并用手写 fixtures + 本地 HTTP 测试覆盖。
- [x] DeepSeek 读 README → 改第一行：观察到 `read` → `edit`，文件第一行变为 `# Edited by pai`（附录 A）。
- [x] DeepSeek reasoning_content 回传：deepseek-reasoner + `PAI_DEBUG_HTTP=1`，第 3 个请求体的 assistant 消息带 `reasoning_content`（附录 B）。日志中 key 形态串 0、Authorization 0。
- [x] DeepSeek `bash ls` 并总结（附录 A）。
- [x] `--resume` 后问"我们刚做了什么"，模型答出 `ls` 与两个文件名（附录 A）。
- [x] session JSONL 逐行可解析，每行有 `type/id/parentId`（无 jq，用 python 逐行 `json.loads` 代替；附录 C）。
- [x] 主代码行数（cloc 未安装，用等价脚本：去空行与注释）：pai-model **1541**、pai-core **1328**（≤2500）；pai-tools 419、pai-cli 505。
- [x] `grep -rn "Legacy\|Adapter\|Compat\b" --include=*.java` 只命中 ArchitectureTest 里的禁词正则本身（`OpenAiCompatProviderImpl` 因 `Compat` 后接字母不匹配 `\b`）。
- [x] 核心层 CJK grep 零命中；TODO/FIXME/XXX 零；核心层 System.out/err 零。
- [x] 本报告。

## 2. `build/check-budgets.sh` 输出

```
pai-model LOC=2180 (max 4000)
pai-core LOC=2226 (max 5000)
pai-cli LOC=568 (max 1500)
budgets OK
```
（脚本口径为非空行，含 Javadoc。）

## 3. `git diff --stat cf3ade5..HEAD`

118 files, +9615/−1；核心主代码 60 files +4980。全部为 M1 规格列出的类型与其实现；`build/check-budgets.sh` 改 1 行（空模块时 `grep -c || echo 0` 输出两行 0 导致整数比较失败，改为 `|| true`）。

## 4. 签名异议

1. `ExtensionHost` 规格标为 internal，但 cli 必须持有它，而 00 §2 规则 3 禁止 core 外 import `..internal..`。现放在 `dev.pai.core.ext`（public final，构造器包私有）。建议规格把它列为公开类型。
2. 两个 Codec 各自嵌套一个相同的 `StreamDecoder` 接口；provider 层需要统一视图（现用包私有适配）。建议提到 `dev.pai.model.codec.StreamDecoder` 共享。
3. 新增的公开成员（只增不改）：`CancelTokenSource`（规格只有 `CancelToken.none()`，没有可取消的实现）、`StreamDecoder.fail(reason, msg)`（取消/HTTP 错误时需要带 partial 的终止事件）、`newDecoder(ModelRef)`、Codec 构造器 `(providerId[, echoReasoning])`、`SseClient(connect, response, forceHttp1, debugBodies)` + `http1()`、`SseHttpException`、`OpenAiCompatProviderImpl.Flags`、两个 `endpoint(URI)`、`Json.parseObject`、`ToolResult.withDetails`、`ToolRegistry.unregister/sourceOf`、`AgentConfig.of` + 默认值常量、`ExtensionLoader.install(List, Path paiHome)`、`JsonlSessionStoreImpl(Path, boolean fsync, Clock)`、`FakeProviderImpl`（放在 pai-model main，供 core 测试与 `--provider fake`）。

## 5. SPI 漏洞

无。

## 6. 偏离与理由

- bash 工具在 Windows 优先用 Git Bash（`PAI_BASH` / git.exe 旁 / Program Files），找不到才 `cmd /c`；规格写 `cmd /c`，但 M1 以 bash 为主、模型发的是 bash 语法。
- bash 输出 >50KB 保留**前** 50KB 并标注剩余字节数（按"截尾"字面理解）。
- `transformContext` 在每次请求前调用，结果作为后续上下文（ADR 0003 写在 TurnEnd 后；首轮前也调用，语义等价）。
- 并行工具用 `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("pai-tool-", 0))`，即 01 §4 已允许的虚拟线程 executor 加命名。
- live 测试设 `PAI_HOME=target/live-home`，session 写在其下而非 `~/.pai`（ENVIRONMENT 要求只在仓库内操作）。session 文件名时间戳为 UTC。
- P3C：报告型；`typeResolution=false`（否则 PMD 6.21 读 Java 21 class 文件全部 "processing error"），38 个无法解析的文件进 `<excludes>`。

## 7. 坑

- PowerShell 5 下原生命令 `2>` + `$ErrorActionPreference="Stop"`：第一行 stderr 即终止管道并杀掉 java（live 调试日志走 stderr）。改用 `cmd /c "java ... < in 2> err"`。
- `mvn ... | Select-Object -First N` 会提前结束管道、中断 Maven。用 `*> log` 再筛。
- `/mnt/user-data/outputs` 下同名文件覆盖后 `device_commit_files` 可能仍推旧内容；先 `rm` 再生成。
- DeepSeek 对 `deepseek-chat`/`deepseek-reasoner` 返回 `model:"deepseek-flash"`；decoder 以请求的 ModelRef 为准（`newDecoder(ModelRef)`）。
- deepseek-reasoner 第一轮回复可能不带 reasoning_content，因此只有带推理的 assistant 消息会回传（附录 B 的请求 3）。
- core 测试无 SLF4J 绑定 → NOP logger（名字不可断言）。
- 控制台是 GBK：`EventPrinter` 检测编码，不支持时 `▸✓✗` 退化为 `> ok x`。

## 8. 建议（未做）

- 拿到 Anthropic key 后用 `RecordFixtures` 扩展录制 anthropic fixtures 并跑两项 live。
- 加 GitHub remote 让 CI/gitleaks 真跑；设备装 `jq`/`cloc`。
- `JsonlSessionStoreImpl.append` 每次全量读文件校验 id（O(n²)），M2 加 leaf/id 缓存。
- DeepSeek thinking 模式可能要求所有 assistant 消息都带 `reasoning_content`（pi 有 `requiresReasoningContentOnAssistantMessages`）；M4 provider 预设表时补 flag。
- REPL：`/help`、思考内容灰显、提示符与流式文本换行美化（留给 M2 TUI）。
- P3C 等阿里发布 PMD 7 版本后切 blocking 并删 excludes。

## 9. 给 M2 的输入

- `EventPrinter` 是 TUI renderer 的雏形；所有展示只依赖 `AgentEvent`。
- 压缩可直接落在 `AgentHooks.transformContext` + `CompactionEntry`；`buildContext` 已实现"摘要 UserMessage + firstKept 起保留"。
- `ContextCompacted` 事件已定义未发出。M2 若加 `prepareRequest` 钩子需改 ADR 0003。

## 附录

**P3C 实际状态**：reporting（`failOnViolation=false`）；maven-pmd-plugin 3.13.0 自带 PMD **6.21.0** + p3c-pmd 2.1.1；`typeResolution=false`；排除 **38** 个文件（records/sealed/模式 switch）；剩余文件 0 processing error，3 条 Priority 2 `LowerCamelCaseVariableNamingRule`，均为误报（PMD 6 把嵌套 `private record Entry/Section/Shell` 当成变量）。

**A. DeepSeek（deepseek-chat）live 输出**（控制台 GBK，符号已退化）
```
model=openai-compat/deepseek-chat session=0e7806f0-... cwd=D:\PaiCLI\pai-next\target\live\chat
> I'll read the file first, then make the change.
> read({"path":"README.md"})
  ok 0.0s
> edit({"path":"README.md","oldText":"# Demo project","newText":"# Edited by pai"})
  ok 0.0s
Done. `README.md:1` is now `# Edited by pai`; line 2 is unchanged.
[usage in=1511 out=147 cacheRead=2176 cacheWrite=0]
$ type README.md  ->  # Edited by pai / This line stays.

> bash({"command":"ls"})
  ok 0.5s
The working directory contains two files: `notes.txt` and `README.md`.

model=openai-compat/deepseek-chat session=608f7bb9-... resumed=4 messages
> We ran `ls` in the working directory and found two files, `notes.txt` and `README.md`.
```

**B. deepseek-reasoner + PAI_DEBUG_HTTP=1 请求体摘要**
```
secret-shaped strings: 0 authorization mentions: 0
request 1: messages=2 roles=['system', 'user']
request 2: messages=4 roles=['system', 'user', 'assistant', 'tool']
   assistant keys: ['content', 'role', 'tool_calls'] | reasoning_content: None
request 3: messages=6 roles=['system', 'user', 'assistant', 'tool', 'assistant', 'tool']
   assistant keys: ['content', 'role', 'tool_calls'] | reasoning_content: None
   assistant keys: ['content', 'reasoning_content', 'role', 'tool_calls'] | reasoning_content: Change first line. ...
```

**C. session JSONL**
```
20261014-142641-0e7806f0-....jsonl: lines=7 parsed=all header=session entries_have_type_id_parentId=True linear_chain=True
{"type":"session","version":1,"id":"0e7806f0-...","cwd":"D:\\PaiCLI\\pai-next\\target\\live\\chat","timestamp":"2026-10-14T14:26:41.051423300Z"}
{"type":"message","id":"0a9a279c","parentId":null,"timestamp":"...","message":{"type":"user","content":[...]}}
{"type":"message","id":"f3b80190","parentId":"0a9a279c","timestamp":"...","message":{"type":"assistant",...}}
```

**行数（主 / 测试，去空行与注释）**：model 1541 / 757，core 1328 / 679，tools 419 / 265，cli 505 / 316。
