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
