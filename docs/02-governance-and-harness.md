# pai-next 流程与 harness（个人开发精简版）

> `00` 管形状，`01` 管写法，本文管流程。个人开发 + 多个 AI session 接力，所以只保留"装一次、以后零成本、红了就是真有问题"的东西。

## 1. 分支与提交

- 每阶段一个分支 `impl/M1`…，验收后合入 `main` 并打 tag `M1`…（M3 要用 `git diff M2..HEAD`）。
- commit 英文一行，前缀 `feat/fix/test/docs/refactor(scope):`。能单独编译通过即可，不要求每个 commit 全绿。
- 不提交 `.env`、key、`target/`。CI 跑 gitleaks 兜底（旧仓库根目录就躺着一个 10KB 的 `.env`）。
- pre-commit 只做 `spotless:apply`（`build/pre-commit`），其余交给 CI。

## 2. CI（`build/ci/verify.yml.example` → 手动放到 `.github/workflows/verify.yml`）

两个 job：
- `verify`：Ubuntu 上 `mvn -B verify`（测试、Spotless、ArchUnit）。Windows 只在合入 `main` 时跑一次——目标用户在 Windows，`bash` 工具和路径问题 Linux 测不出来。
- `gitleaks`。

CI 绿 = 阶段可以合入。报告里自称全绿不算。

## 3. 机器检查（都在 `mvn verify` 或一个脚本里，不需要人记）

| 检查 | 工具 | 防什么 |
|---|---|---|
| 格式 | Spotless + 阿里 p3c 模板 | 格式争论 |
| 模块依赖方向、public 类型只能是 interface/record/enum/sealed/final、禁词后缀、`Enum`/`Impl` 后缀、无中文字面量、无非 final static 字段 | ArchUnit（`pai-cli` 的 `ArchitectureTest`） | 分层塌陷、God class、兼容层 |
| 测试默认开 | Surefire（pom 不许 `skipTests`） | 文档和代码漂移没人发现 |
| 行数：`pai-model` ≤ 4000、`pai-core` ≤ 5000、`pai-cli` ≤ 1500、单文件 ≤ 400；`TODO` = 0 | `build/check-budgets.sh`（CI verify 里跑） | 核心长胖、债务进主干 |
| 阿里手册规约 | P3C-PMD，**报告型**（不阻断；PMD 6 不认 Java 21 语法） | 手册强制项 |
| 覆盖率 | JaCoCo 报告型，审查时看 | 不设门槛，避免为数字写测试 |

**不做**：公开 API 快照（M2 起如果发现签名老漂再加）、Error Prone/NullAway（M2 再考虑，Maven 上配置折腾）、依赖数/配置项数预算、启动时间 CI 检查、docs 所有权 CI 拦截、`@SuppressWarnings` 计数。

## 4. 几条靠自觉的约定（审查时看）

- 实施 session 不改 `docs/00|01|02`、`adr/`、`phases/`；异议写报告。你合 PR 时扫一眼 diff 路径就行。
- 公开签名按规格来；想改先在报告里说，别默默改。M1 结束时主控过一遍公开 API，之后再改就要有理由。
- interface 只允许两种：ADR 列的 SPI，或当前就有 ≥ 2 个实现。只有一个实现 → final class。不预留扩展点，不用"加个默认关的 flag"回避决策。`XxxManager` 只许 `McpServerManager`。
- 命名用 `GLOSSARY.md` 的词；新词先加表。
- provider fixtures 从真实请求录制（脱敏后入库，文件头写日期/模型）。
- 报告用 `reports/TEMPLATE.md`，正文 ≤ 1 页；主控裁决记 `DECISIONS-LOG.md`。
- 支持范围：JDK 21；Linux/macOS/Windows 10+；OpenAI Chat Completions + Anthropic Messages。其他需要时走 ADR。

## 5. 主控自己

ADR 一阶段修订 ≤ 2 次；规格只写合同与验收，≤ 400 行；审报告时至少实际打开 5 个文件。
