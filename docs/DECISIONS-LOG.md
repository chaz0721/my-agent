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
| 2026-10-15 | 规划 | 项目定位重申：完整、可讲清、每个决定有理由的个人项目；不追求功能对标 Claude Code | 后续阶段 M6（上下文与记忆）→ M7（harness 加固）→ M5.5（子代理）后收口，转入面试材料整理 | 00 §5 |
| 2026-10-15 | 规划 | Claude Code 泄露源码的使用边界 | 只读第三方机制解读，不读/不搬泄露代码；借鉴项写进 M6/M7 规格并注明"机制层面" | M6/M7 |
| 2026-10-15 | 规划 | M5.5 子代理 → 改为 M7.5 | 裁决：依赖 M7 的 `Tool.effect(input)`（explore 只读过滤）与路径解析下沉；规格按 Claude Code/Codex 机制解读重写（侧链 transcript、explore 不注入项目上下文、禁止嵌套、abort 树、写冲突预检用真实路径锁）。最终顺序 M6 → M7 → M7.5 → 收口 | phases/M7.5、M5、00 §5 |
| 2026-10-15 | M6 | 偏离 1：微压缩放 `prepareRequest` 而非规格的 `transformContext` | 接受（审查独立核实：`compact()` 要求上下文与 session 投影逐条 equals，放 transformContext 后续摘要全被跳过、Repl 重置 leaf、TUI 打印整行）；ADR 0003 修订记录由主控写；`CompactingHooks` 必须排在 host hooks 之后注册，写进 Javadoc | core/adr/0003 |
| 2026-10-15 | M6 | 偏离 2：微压缩第二档 `keepRecentTurns=1` | 有条件接受：8k 窗口下 system prompt+工具定义约 2.7k，默认档不起作用；条件是加滞回（进入第二档保持到下一次全量摘要） | core |
| 2026-10-15 | M6 | 偏离 3：投影估算 = 原估算 − 节省量 | 按原写法拒绝（审查实验复现：微压缩生效后第二次请求起 usage 已是裁剪后大小，重复扣除等于丢掉 usage 校准，真实投影超阈值却不压缩）；改为记住上次发出时被裁的 toolCallId 集合，只扣新裁部分、集合相同时直接 estimate(view) | core |
| 2026-10-15 | M6 | P1-2 候选重复提出（摘要"合并"带回旧 Decisions → review 后/resume 后再问；y 写重复行） | 必须修：按归一化文本去重（对照 store.all()、pending、被拒集合）；被拒哈希写 `CustomEntry{memory-rejected}` 供 resume 读回；整体拒绝 review 时整批记为被拒 | ext-memory |
| 2026-10-15 | M6 | P2-1..9（摘要标题匹配脆弱+失败退避 3 turn、Files touched 并集/完整路径/排除失败写入、第二档滞回、MemoryFile 临时文件唯一+宽容解码+BOM、topic 黑名单 memory/Windows 设备名、tag `[^\s#]+`、code point 截断、文件头注释、注册顺序 Javadoc） | 一起修，合入前 | core/ext-memory |
| 2026-10-15 | M6 | 验收"压缩后不重读即可继续改"未达成（base prompt 要求先读再改 + edit 需逐字 oldText） | 接受为 M6 已知缺口，归 M7 7D 压缩后文件恢复解决；不改 base prompt | M7 |
| 2026-10-15 | M6 | SPI 漏洞：事件双发/`withListener` 旁路、MICRO 事件到不了 renderer（EventView MICRO 分支死代码）；`UserInteraction` 无自由文本输入（`e` = 保存并提示去文件改）；扩展读不到 CompactionEntry；`SessionEnded` 时终端可交互依赖 cli dispatch 顺序 | 记录：第一条 M7 7A 解决；`e` 语义接受；其余记录 | M7 |
| 2026-10-15 | M6 | `SessionEntriesImpl` 缓存键 (session, leaf) 正确但稳态几乎不命中；Ctrl+C 空闲退出候选静默丢失；requestOverhead 漏 withToolsRegisteredSince 工具；recall 沿用 BM25、topic 在 MEMORY.md 只占一行索引 | 记录：缓存改增量解析随 M7 7D 性能基线做；其余接受/记录 | M7 |
| 2026-10-15 | M6 | 新增公开成员登记：`MicroCompactionPolicy`、`MicroCompactor`、`CompactingHooks.withListener/projected/MICRO_TRIGGER_PERCENT`、`ModelSummarizerImpl.MAX_SUMMARY_CHARS`、`AgentEvent.ContextCompacted.KindEnum` | 接受登记 | core |
| 2026-10-15 | M7 | ADR 0002（effect(input)/concurrencySafe(input)、ShellLexer、白名单下沉）与 ADR 0003（TerminationReasonEnum、ContextStage 封闭 SPI、ContextCompacted 由 loop 独家发出并废除 withListener、TurnStart.estimatedTokens、MAX_OUTPUT_TOKENS 重试、压缩熔断）修订记录由主控写入 | 已写；M6 的 7 条输入合并进 M7 规格开头并扩大授权范围（cli：Ctrl+C 退出路径、SessionEntriesImpl 增量解析） | adr/0002、0003、phases/M7 |
| 2026-10-15 | M7 | 审查 P0：只读 bash 在默认 AUTO_READ 免审 + ShellLexer 不处理 glob → 文件名 `--pre=sh` + `rg TODO *` 不审批执行任意代码（实测） | 必须修：未加引号的 `* ? [` 一律 EXECUTE；M5"含引号即拒"不放宽；`find . -name *.java` 不再只读 | pai-tools |
| 2026-10-15 | M7 | P1-1 只读 bash 路径检测只看文本（symlink、`-rf/etc/passwd` 粘写）；P1-2 `git status/diff` 执行 `.git/config` 里的程序 | P1-1：hitl 对每个非选项词做真实路径解析，任一在 cwd 外转审批；P1-2 裁决 (a)：git 只在 projectTrusted 时免审，受信任项目的残余风险登记为接受 | ext-hitl |
| 2026-10-15 | M7 | P1-3 deny 规则按原始文本 glob 匹配，空白/引号/包装器/路径前缀即可绕过，YOLO 或会话 always 下等于没有 | 必须修：按词序列匹配（复用 ShellLexer 切分，新增 `ShellCommands.simpleCommands`）；解析不干净且存在 bash deny 规则时禁止任何自动放行，至少问一次，无 ui 则 block | ext-hitl/pai-tools |
| 2026-10-15 | M7 | P1-4 ADR 0002 `concurrencySafe(input)` 默认实现忽略旧 `concurrencySafe()`（主控 ADR 的漏洞）；`effectOf` 对"返回更危险值"fail-open | ADR 0002 更正（见修订记录）；代码同步；删 3 个 ext 工具的权宜覆写 | adr/0002/core |
| 2026-10-15 | M7 | P1-5 熔断只计摘要异常，"成功但仍超阈值"会每 turn 压缩一次 | 必须修：也计失败 | core |
| 2026-10-15 | M7 | core 预算 4992/5000 系删 Javadoc 与 3 个旧构造器挤出 | 裁决：core 预算提至 5500（TerminationReason/ContextStage 为 ADR 授权的内核增长）；不得再为预算删 API/压注释；已删构造器（`ModelSummarizerImpl(Supplier)`、`AgentConfig` 6 参、`LoopSnapshot` 5/7 参）接受（无扩展使用） | build |
| 2026-10-15 | M7 | 偏离：ContextStage 3 个默认方法、MICRO 每请求必发、恢复总量 min(25k, room/2)、TurnStart 在管线后发、`/compact` 事件由 Repl 发、AUTO_READ 下只读 bash 免审（M6 时一律审批）、tui ask 先精确匹配、ProjectTrust 加 permissions.json、7A+7D 同 commit | 全部接受（AUTO_READ 免审以 P0-1/P1-1/P1-2 修完为前提）；ADR 0003 补充记录由主控写 | adr/0003 |
| 2026-10-15 | M7 | P2：lengthRetries 改连续计数、FileRestorer 敏感文件排除表、allow 路径规则先规范化、rules() 快照防 CME、proposedPattern 对包装器/解释器/赋值不生成规则、`CompactingHooks` 改名 `Compaction`、补 loop 内自动 SUMMARY 后恢复的 live 证据 | 一起修，合入前 | 多模块 |
| 2026-10-15 | M7 | 记录：MICRO+SUMMARY 同请求 sentTrimmed 偏保守；恢复消息排在最新 prompt 后可能被当最新指令、再压缩时被当用户内容（需 meta 消息类型）；cmd 回退同名 .bat；多会话 permissions 覆盖；SessionEntriesImpl 复制 store 命名规则；bench.sh 未在真实 bash+Java 环境跑过（电脑 bash 为 WSL） | 记录；meta 消息类型留 M7.5 后评估 | 后续 |
| 2026-10-15 | M7 | 复审通过；残留 P2：cmd 回退下 `^`/`%` 绕过 deny、短选项粘写纯字母值不检查、`<restored-files>` 累积致每轮摘要、秘密排除表补全 | 一个 commit 修完合入 main，tag M7，push | pai-core/ext-hitl/cli |
| 2026-10-15 | M7 | ShellLexer 下沉到 core（`dev.pai.core.tool.ShellCommands`）而非规格的 pai-tools：ArchUnit 禁止 ext 依赖 pai-tools，而 hitl deny 匹配需同一切分器 | 接受（即 M5 记下的"Guard 基础设施下沉 core"方向）；ADR 0002 措辞随之以 core 为准 | core |
| 2026-10-15 | M7 | live 暴露：摘要保留的 assistant usage 是压缩前大小，使 P1-5 把每次自动摘要判失败、熔断误触、恢复空间为 0 | 已修（`staleUsage` 按字符估算直到新回复）；记录两处仍用旧 usage：`--resume` 后 staleUsage 为空、ctx% 在压缩后到下次回复前显示旧值 | core |
| 2026-10-15 | M7 | 记录：Linux 带冒号 symlink 可绕过"最后一个冒号后缀"规则（理论）；git 免审依赖启动时信任快照（与 /trust 现有语义一致）；deny 词序列匹配会过度拒绝 `echo git push`（接受）；cli 预算 1930/2000 | 记录；cli 预算收口前再看 | 后续 |
| 2026-10-15 | M7 | GLOSSARY 新增 Termination reason / Context stage / Per-input effect / Read-only table / Permission rule / Restored files / 压缩熔断；01 §3.1 示例 `CompactingHooks`→`ApprovalHooks`，`ContextStage` 实现不加 Impl | 已写 | GLOSSARY/01 |
| 2026-10-16 | M7.5 | M7 报告 §9 三条输入合并进 M7.5 规格；真实路径解析已有两份（PathGuard.real、FileRestorer.inside），写冲突预检会是第三份 | 裁决：core 新增最小 `RealPaths.inside(cwd, raw)`（≤30 行）三处共用，为本阶段唯一授权 core 改动；cli 接线 ≤30 行；子代理从空上下文开始不注入派生消息 | phases/M7.5、core |
| 2026-10-16 | M7.5 | SPI 漏洞：扩展拿不到父 provider / hooks 链 / 工具列表，子代理做不出来（规格要求复用父的三者） | 裁决 A：`ExtensionContext.subLoop(AgentConfig, Predicate<Tool>)` + `Settings.withProvider`；只转发 beforeToolCall/afterToolCall；ADR 0005 修订由主控写。拒绝 B（cli compile 依赖 ext） | adr/0005、core、cli |
| 2026-10-16 | M7.5 | `RealPaths` 按脚本口径 43 行（可执行 22 行）超出"≤30 行"授权 | 接受：口径以可执行行为准，脚本口径只看模块总预算；FileRestorer 先 normalize 的细微行为变化接受 | core |
| 2026-10-16 | M7.5 | 审查 P1-1：父空闲时后台子代理的审批/verify 询问会因终端被 REPL 占用而抛异常，且 afterToolCall 链不隔离异常致写锁泄漏到进程退出（实验证实） | 必须修：后台 run 一律 PLAN 语义只读（规格修订）；前台非只读调用前打 `[sub-agent]` 提示；`WriteLocks.releaseAll(owner)` 兜底；授权 core `HooksChain.afterToolCall` 逐 hook 隔离（≤10 行）与 tui `ask/confirm` 捕获 ISE（≤5 行） | ext-subagent/core/tui/phases |
| 2026-10-16 | M7.5 | 审查 P1-2：锁在 preflight 取、执行后释放，父 `[spawn_agent(fg), edit a]` 被自己的子代理卡 60s（实测） | 必须修：父不参与锁；子代理间顺序死锁与长 bash 占锁接受为已知限制（60s 兜底、文案准确）；记 SPI 漏洞"缺执行前一刻钩子/工具包装能力" | ext-subagent |
| 2026-10-16 | M7.5 | 偏离：写锁按 run 持有而非 per-path ReentrantLock；`LoopProviderImpl` 以单 turn subLoop 做摘要 provider；general 按名字排除 todo 工具；TUI 不渲染 ToolExecutionUpdate；子代理 hook 排在 hitl 之前（cli pom 顺序） | 全部接受；"工具是否写宿主 session"缺自描述记 SPI 漏洞；TUI 进度渲染留收口 | — |
| 2026-10-16 | M7.5 | P2：tools: 空值/未知名 notify、spawn_agent.effect(input) 按类型（PLAN 可派 explore）、补装 hitl 的 auto-read 单测、code point 截断、并发上限 ≤0 notify、subLoop 改 default 抛 UOE、锁测试归位、报告措辞"不含文件正文" | 一起修 | ext-subagent/core |
| 2026-10-16 | M7.5 | 记录：deny 整体禁用的工具仍在子代理列表（调用被拦）；/model 切换后后台子代理压缩用新 provider；cwd 外写入不加锁、Windows 大小写键；PAI_DEBUG_SUBAGENT 非全量；/clear 时 close 阻塞 ≤10s；resultSummary 不过 SecretFilter（与父一致）；explore live 在 yolo 下跑、路径检测靠单测 | 记录 | 后续 |
| 2026-10-16 | M7.5 | 复审通过。新发现 P2：hook 顺序 subagent→hitl 后锁在审批之前取得，用户考虑审批的时间占锁（B 等 A 审批 >60s 则失败）；shaded jar 内扩展顺序无测试守护；tui 捕获 ISE 会吞编程错误；before/after 钩子异常语义不对称（before 抛=block，after 抛=跳过） | 合入 main，tag M7.5，push。四项记录；拆"只读拦截"与"取锁"为两个 hook 留收口 | ext-subagent/tui/core |
| 2026-10-16 | M8 | 用什么衡量"水平" | 裁决：coding 基准是正确口径（pai-next 是 coding agent）；主基准 SWE-bench Lite 30 题分层抽样，**同模型 full vs bare 对照**衡量 harness 贡献，绝对分数只说明模型；不调 prompt、不挑题、单次运行、报告写局限。Terminal-Bench 视结果可选 | phases/M8 |
| 2026-10-16 | M8 | 评测需要非交互入口 | 授权 cli `--print`（退出码按 TerminationReasonEnum）与 `--no-ext`，cli ≤ 2000；评测脚本放 build/eval/ 不计预算；允许在 `D:\PaiCLI\tmp\eval` 建 Python venv 装 swebench（不装全局） | cli/build |
| 2026-10-16 | M8 | **安全事件**：评测第 1 题在宿主机 yolo 下运行，模型用 bash `cp` 覆盖了用户全局 Python site-packages 里 astropy 7.2.3 的 `io/ascii/rst.py`、`fixedwidth.py`（PathGuard 只管 write/edit，bash 无边界） | 裁决：评测不再在宿主机跑 pai；改为在 SWE-bench 实例容器内运行（只读挂载 JRE 与 jar），用户已批准 key 以 `docker run -e VAR`（不带值、从进程环境取）进入临时容器；两次受干扰结果作废；用户自行 `pip install --force-reinstall --no-deps astropy==7.2.3` 修复。产品缺口记录：yolo 应有可选的进程级沙箱/路径白名单 | M8/ENVIRONMENT |
| 2026-10-16 | M8 | 评测暴露真 bug：Windows 上 BashTool 以 argv 传命令给 `bash.exe -lc`，含引号/glob 的命令被 MSYS 二次解析而损坏（M4–M7.5 的 live 命令过于简单未触发） | 修法接受：Windows+Git Bash 下写临时脚本 `bash -l file`，只读判定仍对原始文本；记为评测价值的证据 | pai-tools |
| 2026-10-16 | M8 | 云端会话重启致 M8 开发窗口上下文丢失；电脑 impl/M8 两 commit + 未提交评测脚本完好 | 重开窗口接力；交接 brief 写明已完成项与待办 | 流程 |
| 2026-10-16 | M8 | 评测结果：bare 23/30、full 21/30，McNemar p=0.625；full 多出的扩展 0 次被调用；容器可联网，审查逐题确认 ≥2 题照抄上游修复（django-14667 两组补丁与 gold 100% 相同；sphinx-10451 bare 整份搬运 Sphinx 5.1.0 文件，该题"bare 独赢"系泄漏） | 裁决：**绝对分数作废不可引用**；"同模型 full vs bare 无显著差异 + 扩展未被使用"是有效结论；下一轮三件事一起做：出站白名单 + 宿主代理注入 key、扩展会生效的任务集（Java/TS 让 verify 触发、多步任务）、每题 ≥3 次运行 | reports/eval-swebench |
| 2026-10-16 | M8 | 审查 P1-1：`--no-ext hitl` 静默卸掉审批/Guard（core 钩子默认 allow 无兜底）；P1-2：bash 子进程 key 剔除挡不住 `/proc/1/environ`，报告写成防护 | 必修：hitl/builtin-tools 不可卸载、未知 id 用法错误；措辞改"降低误打印风险"，剔除集合并入用户自定义 apiKeyEnv；MCP stdio 子进程继承完整环境记录不修 | cli/pai-tools |
| 2026-10-16 | M8 | P2：EXIT_USAGE 与 MAX_TURNS 撞码（改 64）、exitCode 显式 switch、`--print` 强制无 UI、result.json 记 jar SHA/模型/数据集 revision/镜像 digest、LAUNCH_FAILED 与 TIMEOUT 区分、"相差 6 道"统计表述更正 | 一起修 | cli/eval |
| 2026-10-16 | M8 | 为守 cli 预算（修完 2016/2000）删除 `--print-file`，改 `-p "$(cat file)"` + 容器 `LC_ALL=C.UTF-8`（POSIX locale 下 JVM 把 argv 按 ASCII 解码，非 ASCII 变 `?`） | 接受；cli 预算 1998/2000 封顶，后续任何 cli 功能先拆模块 | cli |
| 2026-10-16 | M8 | 评测暴露的产品缺口汇总：yolo 下 bash 无进程级边界（宿主事故）；key 可经 `/proc` 读取；MCP 子进程继承 key；默认 verify 规则无 Python；`--print` 在 Windows 的 Git Bash 引号 bug | 记录为"若继续开发"的候选：`--sandbox docker|none`、命令环境策略 SPI 挂点、verify Python 规则（`python -m compileall`） | 后续 |
| 2026-10-16 | M8 | 复审通过。为加强 `--print` 无 UI 测试，pai-model `FakeProviderImpl.echo()` 新增 `!tool <name> <json>` 脚本指令（+20 行，2683/4000）；`buildRepl` 外层 IAE 捕获使扩展 install 的 IAE 也报 64 | 接受（测试用 affordance，`--provider fake` 用户可见行为记入报告）；IAE 范围记录不修。合入 main，tag M8，push | pai-model/cli |
| 2026-10-16 | M8.5 | 第二轮评测的价值评估：能给干净数字，但 SWE-bench Lite 的任务分布决定扩展不会被用，重跑大概率仍无差异；测 harness 贡献需另造任务集 | 裁决：不做第二轮；改做 M8.5 四个小修（扩展指引、Python verify、子代理进度、敏感文件转审批），之后用户自用一周收集问题。cli/core 零改动 | phases/M8.5 |
| 2026-10-16 | M8.5 | 审查 P0：Python verify 默认规则 `python -m compileall {path}` 从 cwd 导入同名模块，模型写 `compileall.py` 即不经审批执行（实测）——破坏 M5 "写构建定义→关闭自动运行"不变量 | 必修：加 `-I`；补"cwd 有 compileall.py 不执行"测试；项目自定义 `{python} -m` 规则同样提醒；mvn/cargo 在 cmd 回退下的同类风险记录 | ext-verify |
| 2026-10-16 | M8.5 | P1-1 Windows 文件名末尾点绕过敏感文件判定（`x.pem.`）；P1-2 `<sub-agents>` 措辞过强且 PLAN 下 spawn_agent 实际不在工具列表（PlanMode 按声明 effect 过滤） | 必修：去末尾点/空格 + realPath 文件名再匹配；措辞收窄为大仓库跨文件定位并写成条件式 "If spawn_agent is available"；只复跑 task 1 | ext-hitl/ext-subagent |
| 2026-10-16 | M8.5 | hitl 顺手修复"无 command 参数的调用被按 bash 检查"：审查确认依据是 `effectOf==READ_ONLY` 且无 command，仅放宽 spawn_agent 只读类型；write/edit/MCP/general 在 AUTO_READ 仍询问、PLAN 仍拦 | 接受 | ext-hitl |
| 2026-10-16 | M8.5 | P2：`id_*` 收窄为 ssh 密钥名、`.env.example/sample/template` 排除；敏感表缺失 fail-closed 用内置最小表；非 ASCII 路径逐个跳过且不回灌模型；`/verify` 排除 venv/site-packages 并不写 `__pycache__`、默认只查 git 跟踪的 .py；子代理进度行格式常量化、摘要去控制字符、取消/失败不显示 done | 一起修 | ext-verify/ext-hitl/tui |
| 2026-10-16 | M8.5 | 记录：8.5D 只覆盖只读调用（`cp .env x` 走普通询问；always 后直接跑）；后台 explore 遇敏感文件从后台线程询问；`requirements.txt` 探测偏宽；自定义 agent 类型的 readOnly 判定决定免审——不可信项目的 agent 定义不得自称 readOnly（后续核）；hitl 运行时被禁用时 cli 应拒绝启动（后续 cli 项）；FileRestorer 改读共享敏感表需 cli 一行 | 记录 | 后续 |
| 2026-10-16 | M8.5 | `<sub-agents>` 措辞收窄后 task 1（pai-next 自身仓库找调用点）不再派 explore，模型直接 grep/read | 裁决：保留收窄版。目标是"该用时用"而非"尽量用"；强措辞下用 23 轮换一个 grep 能答的问题是过度使用。8.5A 验收以 task 2/3 命中 + task 1 不误派为通过 | ext-subagent |
| 2026-10-16 | M8.5 | 复审通过。新 P2：cmd 回退下 `${TMPDIR:-/tmp}` 不展开致假失败回灌（不执行代码）；大仓库 `ls-files` 可能超 Windows 命令行上限；aborted 判定靠字符串约定 | `{tmp}` 占位 Java 侧替换后合入 main，tag M8.5，push；其余记录 | ext-verify |
