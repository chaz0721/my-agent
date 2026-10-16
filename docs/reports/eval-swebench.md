# SWE-bench Lite 30 题：同模型 full vs bare 对照

- 日期 2026-10-16；分支 `impl/M8`；被测构建 `43b59c2`（jar 固定为 `D:\PaiCLI\tmp\eval\pai-cli-43b59c2.jar`，SHA-256 `80E088F6…2DA2614`）。
- 模型：DeepSeek `deepseek-chat`，provider 预设 `deepseek`。两组同题、同 prompt、同参数，均为单次运行，没有调 prompt，没有挑题，也没有重跑失败题。
- 一句话结论：**同模型下 full 与 bare 无显著差异；full 的扩展未被使用；绝对分数因上游泄漏作废；下一轮需出站白名单 + 扩展会生效的任务集 + 每题多次运行。**
- 数字：bare 23/30，full 21/30；两组结果不一致的题有 4 道（只有 bare 解决的 3 道，只有 full 解决的 1 道），McNemar 精确检验 p = 0.625。去掉 6 道联网取过包的题后，b=2、c=1，p = 1.0。**已确认至少 2 题是照抄上游修复**（第 5 节），所以 76.7% / 70.0% 这两个绝对分数作废，不可引用；表中保留这两个数，只用于两组对照。
- 本报告经过独立审查后修订（第 5 节、第 4 节第 1 条、key 措辞）。评测数据没有重跑，结果与修订前相同。

## 1. 方法

| 项 | 取值 |
|---|---|
| 数据集 | `princeton-nlp/SWE-bench_Lite`，split `test`（300 题） |
| 抽样 | 按仓库分层，名额用最大余数法分配；每个仓库内对排序后的 id 用 `random.Random(seed).sample` 抽取；seed **20261016**，n=30 |
| 名额分配 | astropy 1、django 11、matplotlib 2、requests 1、pylint 1、pytest 2、scikit-learn 2、sphinx 2、sympy 8 |
| 清单 | `build/eval/instances.txt`（下表 30 题） |
| 运行位置 | 每题在**该题的官方实例镜像** `swebench/sweb.eval.x86_64.<id>:latest` 里运行：仓库在 `/testbed`（HEAD 是镜像在 base commit 之上做的 "SWE-bench" 提交，与 harness 应用补丁时的基线相同），用镜像自带的 conda env `testbed`。只读挂载 Linux x64 Temurin 21.0.12 JRE（`/opt/jre`）和 jar（`/opt/pai/pai-cli.jar`）；`/out` 挂本题运行目录；`PAI_HOME` 挂 `D:\PaiCLI\tmp\eval\home\<group>\<id>`（全新空目录）；容器 `--rm` |
| 运行前检查 | 容器内先执行 `git status --porcelain`，非空则不跑（DIRTY_TREE）。实际 60 次全部为空 |
| 命令 | `java -jar pai-cli.jar --provider deepseek --model deepseek-chat --cwd /testbed --approval yolo --max-turns 40 --print-file /out/prompt.txt --quiet [--no-ext …]`，外面套 `timeout 1200`；宿主侧超过 1320 s 则执行 `docker kill`。（审查修复后 cli 删掉了 `--print-file`，脚本改为 `-p "$(cat /out/prompt.txt)"`，并加了 `LC_ALL=C.UTF-8`，见第 7 节。这 60 次运行用的是 `--print-file`；逐个检查 60 个 session，prompt 原文都完整） |
| 补丁 | pai 退出后在容器内执行 `git add -A && git diff --cached --binary HEAD > /out/patch.diff`。新增文件也计入补丁，与官方 harness 应用补丁的基线一致 |
| key | `run.ps1` 从 `.env` 把 `PAI_OPENAI_COMPAT_API_KEY` / `PAI_OPENAI_COMPAT_BASE_URL` 读进自身进程环境；`docker run -e NAME` 不带值；其余子进程（git、harness）用剔除了 key 的环境。pai 的 bash 工具执行命令时会从子进程环境里去掉 provider key 变量（`43b59c2`）。这只能**降低误打印的风险**（`env`、`printenv`），**防不住主动读取**：容器内同一用户执行 `cat /proc/1/environ` 或 `/proc/$PPID/environ` 就能读到 java 进程的环境。本次 659 次 bash 调用逐条扫描，没有出现 `env`、`printenv`、`set`、`/proc/*/environ` 这类调用。运行后扫描所有输出中 key 形态的字符串（`sk-…`、`Bearer …`），结果为 0；`API_KEY` 字样也为 0 |
| 评测 | 官方 harness `swebench==4.1.0`，在 Linux 容器里运行（`pai-swebench-harness:4.1.0`，挂 docker.sock）：`python -m swebench.harness.run_evaluation --dataset_name princeton-nlp/SWE-bench_Lite --split test --predictions_path /eval/preds/<group>.jsonl --run_id <group> --instance_ids …` |
| 顺序 | 交错执行：任务按 (题1,bare)、(题1,full)、(题2,bare)… 的顺序排队，由 2 个 worker 依次领取，所以同一题的两组基本同时运行（中间汇报那题是先 bare 后 full 同批启动）。全部 59 次新运行（django-11583 复用中间汇报那次的结果）共用时 23 分钟 |

### prompt 模板（固定，`build/eval/swebench.py` `PROMPT_TEMPLATE`）

```
修改仓库代码使以下 issue 解决；不要修改或新增测试文件；完成后用一句话总结并停止。

<issue>
{problem_statement}
</issue>
```

### 两组配置

| | full | bare |
|---|---|---|
| 参数 | （无额外参数） | `--no-ext tasks,verify,subagent,skills` |
| 加载的扩展 | builtin-tools、hitl(yolo)、memory、mcp（无配置）、tasks、verify、subagent、skills | builtin-tools、hitl(yolo)、memory、mcp（无配置） |
| 模型可见工具 | read/write/edit/bash、remember/recall、todo_write/todo_read、spawn_agent/agent_result/agent_cancel、load_skill | read/write/edit/bash、remember/recall |
| 压缩 | 开 | 开 |
| 首轮 prompt token 中位数 | 2198 | 1450 |

两组的首轮 prompt 相差约 750 token，这部分就是 full 多出的工具 schema 和系统提示段落。

## 2. 结果

| 组 | resolved | 率 | 95% Wilson CI |
|---|---|---|---|
| bare | 23/30 | 76.7% | 59.1%–88.2% |
| full | 21/30 | 70.0% | 52.1%–83.3% |

- 结果不一致的题：只有 bare 解决的是 `django-15498`、`sphinx-10451`、`sympy-11400`，只有 full 解决的是 `django-13590`。McNemar 精确检验（b=3，c=1）双侧 p = 0.625。
- 判定可复现：同一份预测文件用新的 run id（`bare-r2` / `full-r2`）重新评测，resolved 集合与第一次完全一致，见第 7 节。

### 中位数与合计

| 组 | 中位 turns | 中位 tool calls | 中位输入 token | 中位输出 token | 中位耗时 s | 输入合计（其中 cache 命中） | 输出合计 | 空补丁 | 改了测试文件 |
|---|---|---|---|---|---|---|---|---|---|
| bare | 15.5 | 15.0 | 86,057 | 1,598 | 30 | 4,024,542（3,801,088） | 81,147 | 0 | 1 |
| full | 13.0 | 13.0 | 77,130 | 1,840 | 27 | 5,828,131（5,566,080） | 80,926 | 0 | 0 |

- 终止原因：bare 为 COMPLETED 28、MAX_TURNS 2（`django-15814`、`sphinx-10451`，两题都判 resolved）；full 为 COMPLETED 30（其中 `sympy-19254`、`sympy-20049` 正好在第 40 轮以文本结束）。两组都没有 TIMEOUT、PROVIDER_ERROR 或 DIRTY_TREE。
- 工具调用合计：bare 为 bash 306、read 107、edit 65、write 1；full 为 bash 353、read 104、edit 91、write 2。**full 组没有调用过任何一次 todo_*、spawn_agent、load_skill 或 remember/recall**，子代理 turn 为 0。
- 改测试文件：只有 1 次，即 bare `sphinx-10451` 新增了 `tests/roots/test-ext-autodoc/target/starargs_tmp.py`。这是模型自己的试验文件，没有删掉，被 `git add -A` 带进了补丁，但该题仍判 resolved。其余补丁中，除源码外只出现过 changelog / CHANGES / doc 文件（bare 2 次，full 3 次）。
- 成本估算按 DeepSeek 公开价（cache 命中 $0.028/M，未命中 $0.28/M，输出 $0.42/M；以官网为准，这里没有核对账单）：bare 约 $0.20，full 约 $0.26，60 次合计约 $0.47。

### 每题

`fetch` 列是该次运行中从网络拉包或拉代码的 bash 调用次数（`pip download`、带版本号或 `-U` 的 `pip install`、curl/wget URL、git fetch/pull/clone；审查后又补了 `pip index`、`urllib`、`urlopen`、`requests.get(`，重新统计的结果不变），见第 5 节。**fetch 统计只是下界**：通过脚本文件、别名或别的途径联网，正则都看不到。

| # | instance | repo | bare resolved | bare turns | bare tool calls | bare tokens in/out | bare s | bare end | bare fetch | full resolved | full turns | full tool calls | full tokens in/out | full s | full end | full fetch |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `astropy__astropy-14182` | astropy/astropy | no | 8 | 8 | 36k/0.9k | 17 | COMPLETED |  | no | 26 | 26 | 336k/3.7k | 51 | COMPLETED |  |
| 2 | `django__django-11583` | django/django | **yes** | 4 | 3 | 14k/0.5k | 10 | COMPLETED |  | **yes** | 17 | 16 | 143k/1.8k | 29 | COMPLETED |  |
| 3 | `django__django-11999` | django/django | **yes** | 5 | 5 | 10k/0.7k | 11 | COMPLETED |  | **yes** | 13 | 12 | 56k/1.5k | 26 | COMPLETED |  |
| 4 | `django__django-12908` | django/django | **yes** | 11 | 13 | 44k/1.4k | 20 | COMPLETED |  | **yes** | 10 | 9 | 34k/1.0k | 16 | COMPLETED |  |
| 5 | `django__django-13590` | django/django | no | 7 | 6 | 15k/0.6k | 15 | COMPLETED |  | **yes** | 15 | 14 | 74k/2.3k | 30 | COMPLETED |  |
| 6 | `django__django-13757` | django/django | **yes** | 20 | 19 | 226k/9.7k | 71 | COMPLETED |  | **yes** | 9 | 9 | 56k/1.9k | 20 | COMPLETED |  |
| 7 | `django__django-14382` | django/django | **yes** | 3 | 2 | 5k/0.2k | 8 | COMPLETED |  | **yes** | 3 | 2 | 7k/0.2k | 7 | COMPLETED |  |
| 8 | `django__django-14667` | django/django | **yes** | 37 | 36 | 533k/9.3k | 128 | COMPLETED | 3 | **yes** | 30 | 29 | 316k/5.2k | 72 | COMPLETED | 2 |
| 9 | `django__django-14672` | django/django | **yes** | 5 | 4 | 17k/0.4k | 10 | COMPLETED |  | **yes** | 5 | 4 | 21k/0.4k | 9 | COMPLETED |  |
| 10 | `django__django-15347` | django/django | **yes** | 5 | 4 | 9k/0.4k | 10 | COMPLETED |  | **yes** | 4 | 3 | 14k/0.4k | 9 | COMPLETED |  |
| 11 | `django__django-15498` | django/django | **yes** | 16 | 15 | 93k/1.5k | 26 | COMPLETED |  | no | 7 | 7 | 28k/0.7k | 14 | COMPLETED |  |
| 12 | `django__django-15814` | django/django | **yes** | 40 | 41 | 470k/6.8k | 86 | MAX_TURNS | 1 | **yes** | 13 | 13 | 80k/2.1k | 25 | COMPLETED |  |
| 13 | `matplotlib__matplotlib-25433` | matplotlib/matplotlib | **yes** | 26 | 35 | 349k/7.8k | 96 | COMPLETED |  | **yes** | 39 | 45 | 586k/8.4k | 254 | COMPLETED | 1 |
| 14 | `matplotlib__matplotlib-26020` | matplotlib/matplotlib | **yes** | 10 | 10 | 86k/1.4k | 28 | COMPLETED |  | **yes** | 38 | 38 | 660k/6.0k | 92 | COMPLETED |  |
| 15 | `psf__requests-3362` | psf/requests | **yes** | 17 | 17 | 95k/3.0k | 32 | COMPLETED |  | **yes** | 13 | 13 | 62k/1.6k | 28 | COMPLETED |  |
| 16 | `pylint-dev__pylint-6506` | pylint-dev/pylint | no | 31 | 31 | 313k/3.8k | 65 | COMPLETED |  | no | 37 | 41 | 498k/5.8k | 106 | COMPLETED |  |
| 17 | `pytest-dev__pytest-6116` | pytest-dev/pytest | **yes** | 6 | 5 | 12k/0.5k | 16 | COMPLETED |  | **yes** | 7 | 7 | 30k/0.7k | 16 | COMPLETED |  |
| 18 | `pytest-dev__pytest-9359` | pytest-dev/pytest | **yes** | 16 | 16 | 102k/1.9k | 37 | COMPLETED |  | **yes** | 23 | 24 | 197k/3.2k | 52 | COMPLETED |  |
| 19 | `scikit-learn__scikit-learn-10508` | scikit-learn/scikit-learn | no | 4 | 3 | 13k/0.4k | 9 | COMPLETED |  | no | 20 | 19 | 133k/3.1k | 35 | COMPLETED |  |
| 20 | `scikit-learn__scikit-learn-12471` | scikit-learn/scikit-learn | **yes** | 8 | 8 | 30k/1.0k | 16 | COMPLETED |  | **yes** | 6 | 7 | 23k/0.8k | 13 | COMPLETED |  |
| 21 | `sphinx-doc__sphinx-10451` | sphinx-doc/sphinx | **yes** | 40 | 40 | 524k/7.4k | 91 | MAX_TURNS | 2 | no | 17 | 17 | 127k/2.1k | 46 | COMPLETED |  |
| 22 | `sphinx-doc__sphinx-8506` | sphinx-doc/sphinx | no | 19 | 18 | 88k/2.2k | 35 | COMPLETED |  | no | 29 | 29 | 274k/3.6k | 58 | COMPLETED | 2 |
| 23 | `sympy__sympy-11400` | sympy/sympy | **yes** | 20 | 28 | 169k/3.4k | 42 | COMPLETED |  | no | 12 | 12 | 64k/1.1k | 21 | COMPLETED |  |
| 24 | `sympy__sympy-18532` | sympy/sympy | **yes** | 14 | 14 | 67k/1.5k | 118 | COMPLETED |  | **yes** | 27 | 35 | 260k/4.2k | 186 | COMPLETED |  |
| 25 | `sympy__sympy-18621` | sympy/sympy | **yes** | 16 | 15 | 82k/2.1k | 31 | COMPLETED |  | **yes** | 13 | 13 | 98k/1.8k | 26 | COMPLETED |  |
| 26 | `sympy__sympy-19254` | sympy/sympy | no | 25 | 24 | 162k/3.3k | 43 | COMPLETED |  | no | 40 | 40 | 681k/5.6k | 138 | COMPLETED |  |
| 27 | `sympy__sympy-20049` | sympy/sympy | **yes** | 20 | 23 | 261k/4.8k | 80 | COMPLETED | 1 | **yes** | 40 | 39 | 820k/8.4k | 140 | COMPLETED | 2 |
| 28 | `sympy__sympy-21055` | sympy/sympy | **yes** | 15 | 15 | 86k/1.7k | 30 | COMPLETED |  | **yes** | 10 | 10 | 63k/1.1k | 23 | COMPLETED |  |
| 29 | `sympy__sympy-21171` | sympy/sympy | no | 5 | 4 | 21k/0.7k | 12 | COMPLETED |  | no | 6 | 5 | 31k/0.8k | 14 | COMPLETED |  |
| 30 | `sympy__sympy-24066` | sympy/sympy | **yes** | 17 | 17 | 93k/2.0k | 31 | COMPLETED |  | **yes** | 11 | 12 | 57k/1.3k | 22 | COMPLETED |  |

（turns 是主 session 的 assistant 消息数；tokens in 为各轮 input 与 cacheRead 之和，每轮都会重复计入整段上下文；耗时包括容器启动约 1 s。）

## 3. 读数

- **绝对分数作废，不可引用。** 70–77% 明显高于这个模型在 SWE-bench Lite 上常见的公开分数，而且已经确认至少 2 题是照抄上游修复（第 5 节）。另外样本只有 30 题，与公开榜单的运行设定也没有核对。本评测只用于同模型对照。
- full 和 bare 的差别只体现在"上下文里多了约 750 token 的工具和提示"，没有体现在"模型使用了这些能力"上：在 30 题的 full 运行中，todo / 子代理 / skill / 记忆工具的调用次数都是 0；verify 的内置规则只覆盖 Maven、npm、Cargo、Go，在这 9 个 Python 仓库上不会触发。所以这次对照实际测到的是"额外 schema 和提示对同一模型的扰动 + 单次运行的随机性"，**不是** harness 功能本身的贡献。
- 效率上，full 的 turns 和耗时中位数略低（13 对 15.5，27 s 对 30 s），但输入合计高 45%，主要来自几道长题（`matplotlib-26020` 为 660k 对 86k，`sympy-20049` 为 820k 对 261k）。在 n=30 的样本里，这两个方向的差异都是单题随机性的量级。

## 4. 局限

1. **样本小**：n=30。单组 95% 置信区间宽约 30 个百分点，两组区间大面积重叠。配对检验只看 4 道结果不一致的题。能否显著，取决于不一致题的总数 b+c 和它们在两组之间怎么分布，而不是只看 b 与 c 的差。例如精确 McNemar 双侧检验下：b=6、c=0 时 p=0.031；b=7、c=1 时 p=0.070；b=9、c=2 时 p≈0.065。差同样是 6 或 7 道，结果却可能显著，也可能不显著。
2. **单次运行，非确定性**：每题每组只跑了一次，DeepSeek 采样不确定。同一题重跑一次结果就可能翻转。结果不一致的 4 道题里，3 道是"一组很快结束（7–12 轮），另一组多探索了几轮"，看起来更像随机分叉，而不像系统性差异。
3. **对照的自变量很弱**：见第 3 节，full 的扩展没有被使用。要测 harness 的贡献，应该换成扩展会生效的任务（多步重构、需要拆分子任务、Java/TS 仓库上 verify 能触发），或者在 prompt 里显式鼓励使用这些能力（那样就成了另一项实验）。
4. **容器内运行与真实使用不同**：pai 跑在 Linux 实例镜像里，用镜像自带的 Python 环境，bash 是 Linux bash。用户日常是 Windows 宿主 + Git Bash，M8 刚修过的引号 bug 就只在那条路径上出现。这里的结果不覆盖 Windows 路径。另外容器以 root 运行，可以联网，没有任何路径边界。
5. **网络与上游泄漏（已确认）**：见第 5 节。这是绝对分数作废的直接原因。
6. **memory 两组都开着**（spec 原文是 bare 关记忆）。两组的 PAI_HOME 都是全新的空目录，remember/recall 也从未被调用，所以对结论没有影响，但 bare 实际是"4 个内置工具 + remember/recall"。
7. **评测并发**：主评测用 `--max_workers 4`（brief 写的是 2），复评用的是 2。每题都在独立容器里判定，并发数只影响速度，两次判定结果一致。
8. **成本**按公开价估算，没有核对账单。

## 5. 网络取包与上游泄漏（已确认）

实例容器可以联网（pai 本身要访问 DeepSeek）。有 8 次运行，模型用 `pip download <pkg>==<较新版本> --no-binary :all:` 等方式拉取了**比 base commit 更新的发布版**源码，再拿来对照或 diff。

**已确认至少 2 题照抄了上游修复**（独立审查逐题核对，本窗口复核）：

- **`django-14667`**：两组的补丁都与 gold patch 100% 相同。bare 的 session 第 13 轮原话是 "Found the upstream fix… apply exactly this"（jsonl 第 27 行 "Found the upstream fix. `add_deferred_loading` now handles…"，"apply exactly this" 在第 33 行），并且读了 FAIL_TO_PASS 对应的测试。
- **`sphinx-10451`**：bare 把 Sphinx 5.1.0 的 `typehints.py` 整份搬了过来，第 38 轮自己 diff 后输出 `IDENTICAL`（jsonl 第 77–78 行）。**这道题是"只有 bare 解决"的 3 道之一，bare 赢在泄漏上**；full 在同一题没有联网取包，判 no。
- 并非每次联网取包都照抄了。审查抽查的结果是：`sympy-20049` bare 与 gold 重合 72%，`django-15814` bare 0%，`sphinx-8506` full 20%。本窗口另用"gold 改动行中出现在运行补丁里的比例"复算（口径与审查不同，数字因此不同），结果为：django-14667 两组 100%；sphinx-10451 bare 100%、full 6%；sympy-20049 bare 81%、**full 94%**（full 也拉了 1.6/1.7 的 wheel，是否照抄未确认）；django-15814 两组 0%；sphinx-8506 bare 0%、full 33%；matplotlib-25433 两组 100%（gold 只改 1 行，full 取包超时没拿到，bare 根本没取包，不算泄漏）。脚本是 `D:\PaiCLI\tmp\eval\m8\gold_overlap.py`。

各组取包明细：

- bare：`django-14667`（拉了 4.0、4.2.x、5.2，并装进临时 venv 试验）、`django-15814`（4.2）、`sphinx-10451`（5.1.0、5.2.3，并直接 diff 了 `typehints.py`）、`sympy-20049`（1.8）。这 4 题都判 resolved。
- full：`django-14667`（4.1、4.0，并 grep 了 `add_deferred_loading`）、`sphinx-8506`（3.2.1、3.4.0，并 grep 了 `option_desc_re`）、`sympy-20049`（1.6、1.7 wheel），以及 `matplotlib-25433`（3.8.0，120 s 超时，没有拿到）。前三题中两题判 resolved。
- 去掉这 6 道"任一组联网取过包"的题，剩下 24 题：bare 18/24，full 17/24。结果不一致的题剩 b=2（django-15498、sympy-11400）、c=1（django-13590），McNemar p = 1.0。"无显著差异"的结论不变。
- **绝对分数作废，不可引用**：泄漏是确认的，而且 fetch 统计只是下界，剩下 24 题里也不能保证没有别的联网途径。
- git 本身没有泄漏：镜像里 `git rev-list --all` 与 `git rev-list HEAD` 都是 27141（以 django-11583 为例），不含 base 之后的提交。
- 下一轮必须给实例容器加出站白名单，只放行模型 API；key 由宿主侧代理注入，不进容器。见 M8 报告建议第 1 条。

## 6. 产物路径（`D:\PaiCLI\tmp\eval\`）

- `runs\<group>\<id>\`：`prompt.txt`、`run.sh`、`final.txt`（stdout）、`pai.log`（stderr 渲染）、`patch.diff`、`exit_code`、`git_status_before`、`docker.log`、`result.json`
- `home\<group>\<id>\sessions\…\*.jsonl`：完整 session，usage 和工具调用都从这里统计
- `preds\bare.jsonl`、`preds\full.jsonl`：原始预测
- `harness\pai-next-bare.bare.json`、`harness\pai-next-full.full.json`：官方评测 JSON；`harness\logs\run_evaluation\<run_id>\…`：每题评测日志；复评为 `*.bare-r2.json` / `*.full-r2.json`
- `report.md`、`summary.json`：`run.ps1 report` 的输出（上面的表格就来自这里）
- `void-host\`：作废的宿主机运行结果（安全事件之前），不参与任何统计
- `m8\`：本窗口的辅助脚本和日志（run-all.log、evaluate-all.log、audit.py、netres.py、first_turn.py、reeval-*.log）

## 7. 复现

```
powershell -NoProfile -File build\eval\run.ps1 sample [--seed N --out <file>]   # 默认 seed 重抽结果与 instances.txt 逐行一致；seed 1 与原清单只有 1 题重合
powershell -NoProfile -File build\eval\run.ps1 run --workers 2 --jar D:\PaiCLI\tmp\eval\pai-cli-<commit>.jar   # M8 运行：pai-cli-43b59c2.jar
powershell -NoProfile -File build\eval\run.ps1 preds
powershell -NoProfile -File build\eval\run.ps1 evaluate [--run-id X]
powershell -NoProfile -File build\eval\run.ps1 report
```

复评：用 run id `bare-r2` / `full-r2` 重新跑了一次评测步骤，两组的 resolved 集合与第一次逐项相同（`bare first=23 second=23 identical=True`、`full first=21 second=21 identical=True`，复评 `--max_workers 2`，日志 `D:\PaiCLI\tmp\eval\m8\reeval-*.log`）。

审查之后脚本又改了几处（`22acb8e`、`4658541`），都不影响上面这 60 次运行的数据：
- `load_dataset` 固定 `revision=6ec7bb89b9342f664a54a6e0a6ea6501d3437cc2`（即 M8 运行时缓存的那个版本）。harness 的 `run_evaluation` 调用没有传 revision（没有核对 4.1.0 是否支持），它联网解析数据集版本。M8 评测日志里，它解析到的正是 `6ec7bb89…`（`HEAD …/resolve/6ec7bb89…/dataset_infos.json`）。
- `result.json` 增加了 `jar_sha256`、provider、model、max_turns、timeout_s、group_args、dataset/split/revision、image 与 `image_digest`（用 `docker image inspect` 取镜像本身的 RepoDigest，不 inspect 任何运行容器）。已有结果只在 jar 相同时复用，jar 不同就重跑；M8 的 60 个旧结果没有这个字段，按原样保留。
- 容器根本没启动时记为 `LAUNCH_FAILED`，不再记成 TIMEOUT（靠 `/out/started` 区分）。退出码映射与 cli 同步：64 为 USAGE_ERROR，1 为 CRASH。
- prompt 改为 `-p "$(cat /out/prompt.txt)"` 传入。fake provider 冒烟时发现，镜像的 POSIX locale 下 JVM 按 ASCII 解码 argv，中文全变成 `?`；所以 run.sh 加了 `export LC_ALL=C.UTF-8 LANG=C.UTF-8`，冒烟复测 session 里 prompt 完整（`D:\PaiCLI\tmp\eval\smoke\`）。M8 那 60 次用的是 `--print-file`（按 UTF-8 读文件），60 个 session 里的 prompt 原文都完整，不受影响。新脚本同样能驱动旧 jar 43b59c2，旧 jar 也支持 `-p`。
