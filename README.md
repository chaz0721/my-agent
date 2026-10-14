# pai-next

Java 21 终端 coding agent，架构对标 [pi](https://github.com/badlogic/pi-mono)。PaiCLI 的重做版：极小内核 + 扩展。

目前只有文档，代码按阶段由实施 session 产出。

```
docs/
  00-architecture.md        先读这个：模块、依赖方向、五个地基决定、纪律、旧仓库可搬运清单
  01-code-style.md          代码风格：阿里巴巴手册（黄山版）为基准；构建强制层 + 审查约定 + 偏离表
  02-governance-and-harness.md  流程精简版：分支、CI 两个 job、机器检查清单、几条自觉约定
  GLOSSARY.md               术语表（代码命名只用这些词）
  DECISIONS-LOG.md          主控裁决日志（只追加）
  adr/0001..0005            为什么这样决定（不可违反；改它要走修订）
  phases/M1..M5             每阶段规格：范围、公开签名合同、测试清单、验收清单
  HANDOFF.md                开实施 session 用的交接 prompt 模板 + 主控审查清单
  reference/pi-type-mapping.md   读 pi 源码时的 TS→Java 概念映射
  reports/                  每阶段实施完的报告（TEMPLATE.md 为模板）
build/                      行数检查脚本、pre-commit（仅 spotless）、p3c 格式模板、CI 模板（ci/verify.yml.example → 手动放到 .github/workflows/）
```

流程：主控写规格 → 新 session 按 `HANDOFF.md` 实施 → 报告 → 主控审查、裁决异议、更新 ADR/下阶段规格 → 下一阶段。
