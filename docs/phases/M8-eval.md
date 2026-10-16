# M8 规格书：评测（SWE-bench Lite 子集 + 同模型对照）

> 前置：M7.5 通过（tag M7.5）。本阶段不加 agent 功能，只做两件事：一个非交互入口，一套可复现的评测脚本与报告。目的：回答"这个 harness 到底贡献了什么"，用同模型对照而不是绝对分数。
> 预计 cli ≤ 60 行（余量 67），其余在 `build/eval/`（不计预算）。core 零改动。

## 8A. 非交互模式 `--print`

- `pai --print "<prompt>"`（或 `-p`，`--print-file <path>` 读文件）：启动后只跑**一次** `AgentLoop.run`，不进 REPL。
- 输出：assistant 最终文本到 stdout；事件流不渲染 TUI（`Renderer` 用 Plain，可用 `--quiet` 只留最终文本）。
- 退出码按 `AgentEnd.reason`：COMPLETED=0，MAX_TURNS=2，ABORTED=3，PROMPT_TOO_LONG=4，MAX_OUTPUT_TOKENS=5，PROVIDER_ERROR=6，HOOK_ERROR=7。
- `--max-turns N` 已有则复用，没有则加（默认沿用 AgentConfig）。
- 审批：`--print` 下 ui 不可交互（stdin 已被占/非 TTY）；默认模式仍是 AUTO_READ，非只读调用将因无回答而 block——评测必须显式 `--approval yolo`。文档写明。
- session 照常写（便于事后分析 token/工具调用）。
- 测试：FakeProvider 下 `--print` 退出码 7 种各一个（可参数化）；stdout 只含最终文本；非 TTY 不阻塞。

## 8B. 评测脚本 `build/eval/`

- `swebench.py`（Python 3，用 venv，不装全局）：
  1. 取 SWE-bench Lite（`princeton-nlp/SWE-bench_Lite`，HF datasets 或官方 JSON），按仓库分层随机抽 **30** 题，seed 固定写进报告；题目清单落 `build/eval/instances.txt`，之后复用同一清单。
  2. 每题：用官方 `swebench` 包的镜像起容器（`docker run` 挂载），在容器内 `git checkout <base_commit>`；**pai 在宿主机运行**、`--cwd` 指向容器挂载目录（或在容器内运行 jar——两种选一种并写明理由；优先宿主机运行 + 挂载目录，因为 pai 的 bash 工具会在宿主执行，需确保工作目录隔离：每题独立临时 clone，评测完删除）。
  3. prompt 模板固定（issue 正文 + "修改代码使问题解决，不要改测试，完成后停止"），`--approval yolo --max-turns 40 --print`，超时 20 分钟。
  4. 收 `git diff` 为 `patch`，写入预测文件；调官方 `swebench.harness.run_evaluation` 得 resolved。
  5. 记录每题：resolved、turns、tool calls、输入/输出 token（从 session jsonl 读 usage）、耗时、终止原因。
- **两组配置**（同题、同模型 deepseek-chat、同 maxTurns、同 prompt）：
  - `full`：默认扩展全开（hitl yolo、tasks、verify 默认规则、memory 关、subagent 开、压缩开）。
  - `bare`：只有 4 个内置工具；关闭 tasks/verify/subagent/skill 索引/PAI.md 注入/记忆；压缩保留（否则长题直接溢出不公平）——用 `PAI_DISABLE_EXTENSIONS=...` 或等价启动参数（若无则加一个 `--no-ext <id,...>`，算进 cli 预算）。
- 输出 `docs/reports/eval-swebench.md`：抽样方法与 seed、两组 resolved 数与率、每题表、token/耗时中位数、终止原因分布、**局限段**（样本 30、单次运行、DeepSeek 非确定性、harness 差异的统计意义）。
- 可选第三组：aider 或 pi 同题（有时间再做，写明版本）。

## 边界
- Docker 镜像拉取量大（每仓库数 GB），先只拉抽到的仓库；评测目录 `D:\PaiCLI\tmp\eval\`。
- 不改 prompt 模板去"调分"；不挑题；失败题不重跑（单次运行，写明）。
- key 只在 `.env`；脚本从 `.env` 读进进程环境，日志无 key。

## 验收
- [ ] `pai --print` 7 种退出码测试通过；`--approval yolo --print "list files"` 真实 DeepSeek 一次通过。
- [ ] 30 题 × 2 组跑完，`eval-swebench.md` 有完整表格与局限段；`instances.txt`、原始预测与评测 JSON 留在 `D:\PaiCLI\tmp\eval\`（报告写路径）。
- [ ] 脚本可复现：换 seed 能重抽；同清单重跑评测步骤能得到相同 resolved 判定。
- [ ] cli ≤ 2000；core 零改动；报告 `docs/reports/M8-report.md`。
