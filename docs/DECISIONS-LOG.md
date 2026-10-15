# 决策日志

主控对异议/漏洞/偏离的裁决，一行一条，只追加。实施 session 开工前必读，避免重复提问。
格式：`| 日期 | 阶段 | 议题 | 裁决 | 影响的文件 |`

| 日期 | 阶段 | 议题 | 裁决 | 影响 |
|---|---|---|---|---|
| 2026-10-14 | 规划 | 换到 TypeScript 基于 pi 二开 vs 留 Java | 留 Java，按 pi 分层新起骨架 | 全部文档 |
| 2026-10-14 | 规划 | 原地重构旧仓库 vs 新仓库 | 新仓库；旧仓库只做零件库，读后重写不复制 | 00-architecture §7 |
| 2026-10-14 | 规划 | Java 17 vs 21 | 21（sealed/record pattern/switch 模式匹配/虚拟线程） | 01 §1.6 |
| 2026-10-14 | 规划 | OkHttp vs JDK HttpClient | JDK HttpClient；OkHttp 进 banned | M1、01 §1.6 |
| 2026-10-15 | 规划 | 代码风格基准 | 阿里巴巴手册黄山版；Google/Palantir 格式弃用 | 01 全文 |
| 2026-10-15 | 规划 | 日志 API | SLF4J 门面（手册强制），放弃 System.Logger | M1、01 §2 |
| 2026-10-15 | 规划 | 枚举 `Enum` 后缀、实现类 `Impl` 后缀 | 采纳；Impl 仅限服务型接口（Provider/SessionStore/Renderer/Summarizer/TokenEstimator/Approver/McpTransport），Tool/Extension/Hooks/Command 不加 | 01 §3.1，全部签名 |
| 2026-10-15 | 规划 | P3C-PMD 不解析 Java 21 语法 | 先试跑；失败则降为报告型，手册强制项由 Error Prone+ArchUnit+审查兜底；M1 报告须写明实际状态 | 01 §1.2 |
| 2026-10-15 | 规划 | 手册"POJO 用包装类型" | 偏离：record 字段可用基本类型，非空由 NullAway 保证 | 01 §4 |
| 2026-10-15 | 规划 | 手册"禁 Executors 建线程池" | 偏离：允许 newVirtualThreadPerTaskExecutor；平台线程池仍按手册 | 01 §4 |
| 2026-10-15 | 规划 | 公开 API 漂移怎么防 | 入库 `api/*.api` 快照 + `ApiSnapshotTest`；改签名须主控批准 | 02 §3，M1 交付物 |
| 2026-10-15 | 规划 | harness 过严 | 精简为个人开发版：去掉 API 快照、Error Prone/NullAway、依赖/配置预算、smoke、docs-ownership、JaCoCo 门槛；保留 Spotless/ArchUnit/测试默认开/gitleaks/行数预算/禁词 | 02 全文、01 §1、M1、build/ |
| 2026-10-15 | 规划 | 没有 DAG 如何编排 | 采 Claude Code 式：任务清单工具 + 子代理 + 确定性验证钩子 + 只读模式，全为扩展；旧 DAG 只保留"验证"与"写冲突预检"两个思想。M5 完成定义仅 5A+5C+5D，子代理为 M5.5 | phases/M5-orchestration.md（替换旧 M5） |
| 2026-10-15 | M1 | `ExtensionHost` 规格标 internal 但 cli 必须持有 | 裁决：放 `dev.pai.core.ext` 为 public final、构造器包私有；M1 规格同步修正。`load` 必须隔离单个扩展异常 | M1 规格、ExtensionHost |
| 2026-10-15 | M1 | 两个 Codec 各嵌一个相同 `StreamDecoder` 接口 + StreamingCall.Decoder | 裁决：合并为 `dev.pai.model.codec.StreamDecoder`（含 `fail`），三处共用 | pai-model |
| 2026-10-15 | M1 | ERROR/ABORTED 的 assistant 消息带 ToolCall 被回放导致 API 400 | 裁决（必须修）：Codec 回放时跳过 stopReason=ERROR/ABORTED 的 assistant；补"取消发生在 tool_use 流中 → 下一轮请求合法"测试 | 两个 Codec |
| 2026-10-15 | M1 | OpenAI Codec 把来源字段名存进 `Thinking.signature`；跨 provider 回放 400 | 裁决（必须修）：签名只在 providerId 与当前请求一致时回放，否则丢弃（thinking 降级为不发送）。允许 signature 承载 provider 私有数据，但消费方必须校验 providerId | ADR 0001 追加修订记录 |
| 2026-10-15 | M1 | `cwd/.env` 可改写 BASE_URL 导致 key 外泄 | 裁决（必须修）：key 与 base URL 必须来自同一配置层——取包含 key 的最高优先层，其 BASE_URL 缺失则用 provider 默认，忽略其他层的 BASE_URL；补测试 | pai-cli EnvConfig/ProviderSetup |
| 2026-10-15 | M1 | 预算脚本 4000/5000 vs 验收 ≤2500 | 裁决：不冲突。脚本是终态上限（硬），验收数字是"M1 做多了"的信号（软）。保持 | — |
| 2026-10-15 | M1 | Anthropic fixtures 手写、live 未跑 | 裁决：接受延后，M2 开头若 key 到位先补；不阻塞合入 | — |
| 2026-10-15 | M1 | loop 对无 TOOLS 能力的模型也发 tools | 裁决：按 `ModelRef.capabilities` 过滤，无 TOOLS 则 tools 为空列表 | AgentLoop |
| 2026-10-15 | M1 | 钩子线程语义 | 记录：串行模式 before 钩子在执行任何调用前对全部调用预检；并行模式 afterToolCall 在 worker 线程执行，钩子须线程安全。写进 AgentHooks Javadoc；M4 HITL 规格要引用 | AgentHooks |
| 2026-10-15 | M1 | 仓库无 remote，CI/gitleaks 从未运行 | 待用户决定是否建 GitHub 仓库 | — |
| 2026-10-15 | M1 | item 3 细化：`--base-url` 被 key 来源层限制 | 裁决：CLI/env 的 URL 始终接受（用户本人提供）；仅 .env 文件来源的 URL 要求与 key 同一文件 | ProviderSetup |
| 2026-10-15 | M1 | 复审 N1：恶意仓库 `.env` 可把 pai 指向攻击者端点 | 裁决：M1 接受风险，启动时醒目提示 provider 配置来自 ./.env；根治为 M4 ext-hitl 的项目信任确认 | cli、M4 规格 |
| 2026-10-15 | M1 | 复审 N3：签名回放只按 providerId | 记录：M4 预设表后改 provider+model | M4 规格 |
| 2026-10-15 | M1 | M1 合入 | 复审 15/15 ✓ + N2 修复后合入 main，tag M1 | — |
| 2026-10-15 | M2 | `AssistantMessage` 新增第 8 分量 `toolCallDiagnostics`（保留 7 参构造器） | 接受；替代 M1 的 errorMessage 字符串协议 | pai-model |
| 2026-10-15 | M2 | `LoopState.contextOverflow()/cancel()`、`AgentConfig.retryOnceOnContextOverflow/withModel`、`CommandContext.sendUserMessage`、`Renderer.onInterrupt` | 接受（均为新增；sendUserMessage 是 ADR 0005 已有能力 M1 漏实现） | core/tui |
| 2026-10-15 | M2 | JLine 4 在 JDK 21 需 `--enable-native-access=ALL-UNNAMED`，否则回落 Plain | 接受；M3 交付 `pai.cmd`/`pai` 启动脚本内置该参数 | M3 规格 |
| 2026-10-15 | M2 | 压缩钩子无法可靠拿到 firstKeptEntryId；ExtensionContext 无 cwd | 裁决：M3 给 `LoopState` 加 `leafEntryId()`、给 `ExtensionContext` 加 `Path cwd()`（SPI 变更，ADR 0005 修订记录由 M3 写） | M3 规格 |
| 2026-10-15 | M2 | 工具行不做同一行 ▸→✓ 原地更新，改为 Status 区 + 完成行 | 接受（避免手写光标移动） | tui |
| 2026-10-15 | M2 | 复审：`/compact` 在 fork 后用 store leaf 当当前分支，压缩节点挂错分支 | 必须修：CompactingHooks 接收调用方 leaf，对齐检查逐条 equals；compactNow 按 leaf 变化判断 | core/cli |
| 2026-10-15 | M2 | overflow 正则过宽、摘要 transcript 无上限、prepareRequest 一个钩子失败全部丢、models.json 缺 capabilities 无工具 | 应该修，合入前一起改 | core/cli |
| 2026-10-15 | M2 | 新增公开成员 `CompactingHooks.compact`、`CompactionPolicy.splitIndex/defaults`、`SessionTree`/`Projection`、`SkillRegistry.body/roots`、`ProjectContext.candidates` | 接受登记 | core |
| 2026-10-15 | M2 | pai-tui 预算 | 1500 行，M3 纳入 check-budgets | 02 §3 |
| 2026-10-15 | M2 | 记录：无内置 resources skills 根；skill 名未校验；`/tree` 全量输出；PAI.md `@import` 允许绝对路径（M4 信任确认处理）；Anthropic Codec 不看 IMAGE_INPUT（M4） | 记录 | — |
| 2026-10-15 | M2 | 复审通过；`CompactingHooks` 公开构造器改为 `(Target, Supplier<Optional<String>> leafId)`，`Target` 公开 | 接受；合入前补 JSONL 往返 equals 测试并重跑 live 压缩 | core |
| 2026-10-15 | M2 | M2 合入 | 复审 9/9 ✓ 后合入 main，tag M2 | — |
| 2026-10-15 | M3 | `CompactingHooks.Target` 改为 `(session)`，leaf 来自 `LoopState.leafEntryId()` | 接受（M2 登记的签名被 M3 的 SPI 取代） | core |
| 2026-10-15 | M3 | 新增公开成员 `AgentLoop.withLeaf`、`ExtensionLoader.install(List,Path,Path)/CORE_API_VERSION/DESCRIPTOR`、`ExtensionHost.statuses()`、`ExtensionStatus`、`SkillsExtension()/defaultRoots`；`McpTransport.onClosed`、`close()` 不抛受检异常；`McpServerManager` 方法名 configure/startAll/reload/stopAll/servers | 接受登记 | core/ext-mcp |
| 2026-10-15 | M3 | SPI 漏洞 1：扩展拿不到 pai home，靠 configDir 推导 | 裁决：M4 加 `ExtensionContext.paiHome()`（ADR 0005 修订），并把目录布局写成合同 | M4 规格 |
| 2026-10-15 | M3 | SPI 漏洞 2：ExtensionHost.Context 的注册表非线程安全 | 裁决：M4 作为 core bug fix 改为线程安全（ConcurrentHashMap/CopyOnWrite），MCP 侧仍保持串行 | M4 规格 |
| 2026-10-15 | M3 | SPI 漏洞 3：扩展无用户通知通道 | 裁决：M4 加 `ExtensionContext.notify(String)` 最小接口（tui 渲染为一行提示），ADR 0005 修订 | M4 规格 |
| 2026-10-15 | M3 | `<cwd>/.pai/mcp.json` 与 `.pai/extensions/*.jar` 执行仓库自带代码 | 并入 M4 项目信任确认（与 N1 同类）；MCP 工具 effect=UNKNOWN，ext-hitl 默认要求审批 | M4 规格 |
| 2026-10-15 | M3 | 复审 M-1：项目目录 jar 加载无提示 | 必须修：renderer 醒目提示（同 .env） | cli |
| 2026-10-15 | M3 | S-1 首屏被 MCP 启动阻塞 ≤8s | 裁决：onLoad 只启动；首次模型请求前 awaitReady(8s) | ext-mcp |
| 2026-10-15 | M3 | S-7 `${VAR}` 作用域与 cmd /c 注入 | 裁决：用户级 mcp.json 只用 env+~/.env；项目级才用 cwd/.env；cmd /c 参数含元字符拒绝启动 | ext-mcp |
| 2026-10-15 | M3 | S-8 ADR 0005 修订多出的约定句 | 裁决：改为过渡措辞，M4 起 Context 线程安全 | adr/0005 |
| 2026-10-15 | M3 | S-2..S-6、S-9、R-2、R-5 | 应该修，合入前一起 | ext-mcp/cli |
| 2026-10-15 | M3 | 记录：协议版本不校验、404 不重连、resources/list_changed 未处理、stdio 行长无上限、子进程继承全部环境变量 | 记录；环境变量继承并入 M4 审批/信任 | M4 |
| 2026-10-15 | M3 | 复审通过；S-1 做法稳妥但暴露 SPI 观察：loop 在 prepareRequest 之前算工具列表，钩子只能在请求体里补 | 裁决：M4 core bug fix——`AgentLoop` 在 prepareRequest 之后再从 registry 取 definitions（与钩子补入的去重合并），钩子不再需要补工具；hook 顺序（jar 扩展在 classpath 扩展之后）写进 ADR 0005 | M4 规格 |
| 2026-10-15 | M3 | M3 合入 | 复审 12/12 ✓ + 3 小项后合入 main，tag M3 | — |
| 2026-10-15 | M4 | cli 内置 `/memory`（列 PAI.md）与 ext-memory 的 `/memory` 重名 | 裁决：cli 的改名为 `/context`（术语表 Project context） | cli |
| 2026-10-15 | M4 | `pai-ext-*` 预算 2000 按脚本口径（含注释）ext-mcp 已 2171 | 裁决：ext 预算改为 2500/模块（脚本口径统一） | build |
| 2026-10-15 | M4 | ext-mcp 需知道项目是否受信任 | 裁决：加 `ExtensionContext.projectTrusted()`（第 4 个 SPI 方法，ADR 0005 修订一并写）；core 加 `ProjectContext.load(paiHome, cwd, trusted)` 重载 | core |
| 2026-10-15 | M4 | loop 在 prepareRequest 后合并工具的方式 | 裁决：hook 返回的 tools ∪ (registry 现在 − registry 调用前)，不是整表重加——保留钩子有意删除工具的能力（M5 只读模式） | core |
| 2026-10-15 | M4 | 首个开发窗口被安全过滤器拦截（写危险命令黑名单触发），4A 已写未验证 | 处理：主控同步 4A 到电脑，开新窗口从 verify 4A 起继续 | — |
| 2026-10-15 | M4 | CommandGuard 验收：模型拒绝主动发起递归删除，用其提议的无害变体 + 表驱动 19/13 验证 | 接受为通过（守卫在审批之前拦截、不可批准，已证明） | — |
| 2026-10-15 | M4 | 新增 `ExtensionHost.Settings(paiHome,cwd,projectTrusted,ui,notifier)`、`ExtensionLoader.install(List,Settings)`、`ApprovalDecisionEnum` | 接受登记 | core/ext-hitl |
| 2026-10-15 | M4 | cli 预算 1488/1500 迫使删掉 N1 精确提示、没加 `--approval` | 裁决：cli 预算提到 2000（信任/审批接线是合理增长）；补 `--approval ask|auto-read|yolo`；N1 精确提示不恢复（信任确认已根治） | build/cli |
| 2026-10-15 | M4 | hitl/memory 作为 drop-in jar，默认安装无审批 | 裁决：ext-hitl 改为 cli 的 runtime 依赖（默认带审批）；mcp/memory 仍 drop-in，打包分发问题留 M5 | cli pom |
| 2026-10-15 | M4 | ASK 模式 read 也审批太吵 | 裁决：默认模式改为 AUTO_READ（只读工具在 PathGuard 内免审），ASK 可选 | ext-hitl |
| 2026-10-15 | M4 | 复审 M-1..M-4：管道下 ui() 存在、PathGuard 悬空 symlink fail-open、未信任 @import 经 symlink、审批提示未转义控制字符 | 必须修（全部） | cli/ext-hitl/core |
| 2026-10-15 | M4 | S-1..S-9（CommandGuard 引号/包装绕过、bash 不在 PathGuard 的文档、AUTO_READ 下 read 需过 PathGuard、MCP command 参数显示、并行 `a` 重问、记忆注入破 cache、tags 不过 SecretFilter、SecretFilter 范围、非交互 /memory clear） | 应该修，合入前一起 | ext-hitl/ext-memory |
| 2026-10-15 | M4 | S-10 ADR 0005 修订由实施方自写 | 主控逐句核过后背书；下次仍须由主控写。`notify` 在 Plain 下走 stdout（与普通输出同流），规格"stderr"作废 | adr/0005 |
| 2026-10-15 | M4 | 记忆注入方式 | 裁决：改为与查询无关——会话开始时取最近 N(≤5) 条置顶，固定不变；recall 只作为工具 | ext-memory |
| 2026-10-15 | M4 | ArchUnit 的 stdout/CJK 规则扩展到 `dev.pai.ext..`；retry 提示改走 notify；models.json 条目缺 capabilities 时沿用预设的 sendTools | 一起做 | cli/ext/model |
| 2026-10-15 | M4 | 复审通过；合入前补：R8/iex 残余漏拦、R5 `$(curl)` 收窄到 shell 包装、Unicode 方向/零宽字符转义、MCP 越界文档 | 一个 commit 后合入 main，tag M4，push | ext-hitl |
| 2026-10-15 | M4 | `--approval` 经 System property 传给扩展（全局状态） | 记录：M5 改为经 `ExtensionHost.Settings` 传启动参数（SPI：`ExtensionContext.option(String)`），同时解决"扩展拿不到启动参数"漏洞 | M5 规格 |
| 2026-10-15 | M4 | 记录：Step reasoning_format、GLM 多模态 URL、golden 无日期、MemoryStore realPath、cwd=home 边界、CommandGuard 文本匹配的固有误拦（引号字面量） | 记录 | — |
| 2026-10-15 | M5 | SPI 漏洞：扩展无法写 session CustomEntry（5A 任务清单做不出来） | 裁决：授权 `ExtensionContext.appendEntry/latestEntry`（ADR 0005 修订由主控写）；拒绝"从最后一次 todo_write 调用推导"的绕法（压缩会丢） | core |
| 2026-10-15 | M5 | SPI 漏洞：ext-verify 拿不到内置 bash、调不到 CommandGuard | 裁决：授权 `ExtensionContext.tool(name)` 只读查找；验证命令非模型生成，不走审批/Guard，但 `<cwd>/.pai/verify.json` 需 projectTrusted、规则在会话开始 notify 列出、回灌标明来源；跨扩展共享 Guard 留 M6（下沉 core policy 包） | core/ext-verify |
| 2026-10-15 | M5 | `Renderer.onModeCycle(Runnable)`（Shift+Tab 入口）、`ExtensionHost.Settings` 7 分量（走 5 参 + with*）、命令名 `/approvals ask|auto-read|plan|cycle` | 接受登记 | tui/core/cli |
| 2026-10-15 | M5 | GLOSSARY 把 task 列为 Run 的禁用词，而模块叫 tasks | 裁决：新增术语 Task list（`todo_write`、`CustomEntry tasks`）= 模型维护的工作清单；"task"作为 Run 的同义词仍禁用 | GLOSSARY |
| 2026-10-15 | M5 | SPI 漏洞 3–6（跨扩展共享 Guard/只读白名单、扩展无法驱动 TUI、CommandContext 拿不到最近 assistant 文本、无法列出全部工具） | 记录；M5.5/M6 处理：Guard 与只读白名单下沉 core `policy`；`ExtensionContext.tools()` 列表 | 后续 |
| 2026-10-15 | M5 | 中文 Windows 上 bash 子进程（javac）输出 GBK 被按 UTF-8 解码成乱码 | 列为 M5 审查的"应该修"候选，由审查员评估修法 | pai-tools |
| 2026-10-15 | M5 | 复审 M-1 只读白名单被引号/反斜杠拼接绕过；M-2 PLAN 下白名单 bash 免审读 cwd 外文件 | 必须修：命令含引号/反斜杠即拒；PLAN 下参数含 `/`开头、`~`、`..`、盘符、`%` 转审批（方案 b） | ext-hitl |
| 2026-10-15 | M5 | S-1 verify 的"写构建文件→自动执行"提权 | 裁决：本轮写入命中构建定义文件（package.json/pom.xml/Cargo.toml/build.rs/go.mod/.mvn/**）则本轮跳过自动运行并 notify；每会话首次自动运行前 `ui().confirm` 一次，无 ui 只 notify 不运行 | ext-verify |
| 2026-10-15 | M5 | S-2..S-9（/plan save 来源、/verify 可取消、BashTool GBK 按行回退解码、ArchUnit ext 互不依赖、ExtensionHost 拆 Context、截断尾部、npm 默认规则、Repl 级集成测试） | 应该修，合入前一起 | 多模块 |
| 2026-10-15 | M5 | `/plan save` 创建 `.pai/` 后触发信任询问 | 一起修：信任询问只看 `.env`、`.pai/mcp.json`、`.pai/extensions/`、`.pai/verify.json`，不看 plan.md | cli |
| 2026-10-15 | M5 | `SessionAccess` 实现命名 | 裁决：`SessionAccess` 列入 01 §3.1 服务型接口清单，实现改名 `SessionEntriesImpl` | cli/01 |
| 2026-10-15 | M5 | 复审第二轮：S-3 修法引入回归（/compact 期间 Ctrl+C 失效）；verify 构建文件跳过可分两轮绕过；mayLeaveCwd 漏检 | 必须修 N-1；N-2 粘滞 buildChanged 标记 + confirm 问句明示风险；N-3 一起修。修完合入 M5 | cli/ext-verify/ext-hitl |
| 2026-10-15 | M5 | 5E hooks.json 若实现必须加入信任询问清单 | 记录 | M6 |
