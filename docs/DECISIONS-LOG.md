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
