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
