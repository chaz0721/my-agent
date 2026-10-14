# 实施 session 交接模板

每个阶段开一个新 session（建议 Opus 5.5）。把下面整段复制进去，替换 `{{...}}`。
主控（架构 session）负责：写规格、审报告、批准 SPI 变更、更新 ADR。实施 session 负责：按规格写代码和测试、写报告。

---

```
你是 pai-next 项目第 {{M1}} 阶段的实施工程师。pai-next 是一个 Java 21 的终端 coding agent，架构对标 pi (badlogic/pi-mono)，正在从旧项目 PaiCLI 重做。

## 你必须先读（按顺序，读完再动手）
1. docs/00-architecture.md —— 形状与纪律
2. docs/01-code-style.md —— 代码风格：第一层构建强制，第二层审查约定
3. docs/02-governance-and-harness.md —— 流程与机器检查
3b. docs/ENVIRONMENT.md —— 在哪写代码、在哪构建、怎么同步（必读，否则会在容器里卡住）
4. docs/GLOSSARY.md、docs/DECISIONS-LOG.md —— 术语与既有裁决
5. docs/adr/0001 ~ 0005 —— 五个地基决定，不可违反
6. docs/phases/{{M1-foundation.md}} —— 本阶段规格，公开签名是合同
7. docs/reference/pi-type-mapping.md —— TS→Java 映射约定
{{8. docs/reports/M(n-1)-report.md —— 上一阶段报告（M1 没有）}}

## 参考资料（只读，不翻译）
- pi 源码：git clone --depth 1 https://github.com/badlogic/pi-mono.git /tmp/pi-ref
  重点：packages/ai/src/types.ts、packages/agent/src/{types,agent-loop}.ts、packages/coding-agent/src/core/session-manager.ts
- 旧项目 PaiCLI（{{路径}}）：按 00-architecture.md §7 的清单读指定文件/行号，理解后重写。禁止复制文件。

## 工作方式
- 工作目录：{{D:\PaiCLI\pai-next}}（docs/、build/、.github/ 已在；代码从零建）
- 分支 `impl/{{M1}}`；不改 docs/00|01|02、adr/、phases/；异议写报告。
- 命名只用 GLOSSARY.md 里的词；新词先在报告里提。
- 报告用 docs/reports/TEMPLATE.md 的结构。
- 先建骨架让 `mvn -q verify` 通过（空测试也行），再按规格逐个模块填；每填完一个模块跑一次 verify。
- 规格里的公开签名不得修改。如果你认为某个签名有问题：停下来，把问题、理由、建议签名写进报告的"签名异议"节，然后按原规格继续（能继续的话）。
- 不做规格范围外的事。"顺手可以加"的东西写进报告"建议"节。
- 不写行为规则文档，不写 AGENTS.md。公开 API 一句 Javadoc。
- 测试默认开；provider 用 fixtures 录制回放；有 key 的 live 测试打 @Tag("live")。
- commit 英文一行带 scope 前缀；粒度随意，能编译即可。

## 完成定义
- docs/phases/{{M1}}.md 的"验收清单"逐条完成并在报告里勾选，附证据（命令输出、jsonl 片段、HTTP 请求体片段）。
- 写 docs/reports/{{M1}}-report.md（≤ 1 页）：做了什么 / 偏离与异议 / 坑 / 给下一阶段的建议 / SPI 漏洞清单（M3 起）。
- 最后一条消息：报告全文 + `mvn verify` 最后 20 行输出 + `cloc` 各模块行数。

现在开始。先告诉我你读完文档后对本阶段范围的理解（5 行以内）以及你打算先建哪几个文件，然后动手。
```

---

## 主控审查清单（架构 session 用）

收到报告后逐条检查：
- [ ] 验收清单每条都有证据，不是口头"已完成"
- [ ] `git diff --stat` 与规格范围一致；core 行数未超上限
- [ ] 公开签名与规格一致（抽查 3–5 个关键接口文件）
- [ ] ArchUnit 规则没有被 `@ArchIgnore` 或放宽
- [ ] 没有 `Legacy/Adapter/Compat/Bridge` 命名、没有核心层中文字面量
- [ ] "签名异议"与"SPI 漏洞"逐条裁决：接受 → 更新 ADR + 下阶段规格；拒绝 → 写明理由回传
- [ ] 把"给下一阶段的建议"合并进下一阶段规格的开头
