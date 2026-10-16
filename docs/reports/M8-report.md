# M8 实施报告

- 分支：`impl/M8`（电脑 `D:\PaiCLI\pai-next`），未 push，未合入。
- CI：没有 remote，只在本机跑了 `mvn -B -ntp verify`，结果 BUILD SUCCESS，共 887 个测试，1 个 skipped（`BenchTest`）。`build/check-budgets.sh` 输出 budgets OK，cli 为 1998/2000。
- 实施 session：Claude Opus 5.5，2026-10-16。中途云端会话重启，开发窗口丢失了上下文，由第二个窗口接力：第一个窗口完成了 `6bdbb47`、`f0438fb` 和评测脚本草稿；第二个窗口完成了 key 剔除、容器内运行、全量评测和两份报告。评测结果见 `docs/reports/eval-swebench.md`。

## 1. 验收清单

- [x] **`pai --print` 7 种退出码测试通过；`--approval yolo --print "list files"` 真实 DeepSeek 一次通过。**
  - `MainPrintTest.every_end_reason_has_its_exit_status` 用 `@EnumSource(TerminationReasonEnum.class)` 参数化，FakeProvider 下 COMPLETED=0、MAX_TURNS=2、ABORTED=3、PROMPT_TOO_LONG=4、MAX_OUTPUT_TOKENS=5、PROVIDER_ERROR=6、HOOK_ERROR=7 各跑一例。同一个类里还有：`quiet_print_writes_only_the_final_text_and_records_the_session`（stdout 只含最终文本，session 照常写）、`print_file_and_plain_output_without_quiet`、`bad_print_options_are_usage_errors`、`no_ext_skips_extensions_by_id`。verify 日志：`Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in dev.pai.cli.MainPrintTest`。stdin 是 `/dev/null` 或非 TTY 时不会阻塞：评测的 60 次运行都是 `< /dev/null`，全部正常退出。
  - 真实 DeepSeek：第一个窗口在宿主机执行 `--approval yolo --print "list files" --quiet`，exit=0（`6bdbb47`）。在此基础上，本阶段 60 次评测运行都是真实 DeepSeek 下的 `--print-file … --quiet`：58 次 exit 0，2 次 exit 2（MAX_TURNS），没有 6（PROVIDER_ERROR）。
- [x] **30 题 × 2 组跑完，`eval-swebench.md` 有完整表格与局限段；`instances.txt`、原始预测与评测 JSON 留在 `D:\PaiCLI\tmp\eval\`（报告写路径）。**
  - 60/60 次运行都有 `result.json`；harness 两组都是 `Instances submitted: 30 / completed: 30 / errors: 0 / empty patches: 0`。bare resolved 23，full resolved 21。
  - 预测文件在 `D:\PaiCLI\tmp\eval\preds\{bare,full}.jsonl`，评测 JSON 在 `D:\PaiCLI\tmp\eval\harness\pai-next-{bare,full}.{bare,full}.json`，session 在 `D:\PaiCLI\tmp\eval\home\<group>\<id>\`。完整路径清单见 eval 报告第 6 节。
- [x] **脚本可复现：换 seed 能重抽；同清单重跑评测步骤能得到相同 resolved 判定。**
  - 用默认 seed 重抽，输出与 `build/eval/instances.txt` 逐行相同（`default identical: True`）；用 `--seed 1` 重抽得到另一份 30 题清单，与原清单只有 1 题重合（`D:\PaiCLI\tmp\eval\m8\instances-seed1.txt`）。
  - 复评：用 run id `bare-r2` / `full-r2` 对同一份预测重新评测，两组的 resolved 集合与第一次相同（`bare first=23 second=23 identical=True`、`full first=21 second=21 identical=True`）。
- [x] **cli ≤ 2000；core 零改动；报告 `docs/reports/M8-report.md`。** cli 1998。`git diff --stat main..impl/M8 -- pai-core` 为空。本阶段对产品代码的改动只在 pai-cli（`6bdbb47`）和 pai-tools（`f0438fb`、`43b59c2`）。

## 2. `build/check-budgets.sh` 输出

```
pai-model LOC=2663 (max 4000)
pai-core LOC=5249 (max 5500)
pai-cli LOC=1998 (max 2000)
pai-tui LOC=1008 (max 1500)
pai-ext-hitl LOC=1611 (max 2500)
pai-ext-mcp LOC=2166 (max 2500)
pai-ext-memory LOC=1187 (max 2500)
pai-ext-subagent LOC=1148 (max 2500)
pai-ext-tasks LOC=380 (max 2500)
pai-ext-verify LOC=628 (max 2500)
budgets OK
```

## 3. `git diff --stat main..impl/M8`

见文末附录（最后一条消息里也有原样输出）。逐个 commit 说明：

- `6bdbb47` cli：`--print/-p/--print-file`、`--quiet`、`--max-turns`、`--no-ext`，退出码按终止原因区分。属于 8A，spec 授权。
- `f0438fb` pai-tools：Windows 下 BashTool 把命令写进临时脚本，再交给 Git Bash 执行。这是评测暴露的真实 bug，见第 7 节。
- `43b59c2` pai-tools：BashTool 执行命令时，从子进程环境里去掉 `PAI_*_API_KEY` 和内置预设的 key 变量（`DEEPSEEK_API_KEY` 等），并加了测试 `BashToolEnvTest`。原因：评测改为在容器里以 yolo 运行后，模型执行 `env` 就能把 key 打进 transcript，而 transcript 会落到宿主盘上。改动只有 19 行，其余环境变量原样保留。这项不在 spec 列出的范围内，是新运行方式的必要前提，见第 6 节。同一个 commit 里还带了主控放在工作区的 `docs/DECISIONS-LOG.md` 三条 M8 记录。
- `b80b92f` 加 `build/eval/` 全套脚本；最后一个 eval commit 给 report 加了 fetch 统计和格式调整。`build/eval/` 不计预算。

## 4. 签名异议

无。

## 5. SPI 漏洞

没有发现新的 SPI 漏洞。有一个产品层面的缺口，评测正好撞上：hitl 的 yolo 模式下，bash 没有任何进程级边界。PathGuard 只管 write/edit，扩展 SPI 也没有给 bash 提供"运行环境策略"的挂点，比如 env 过滤、cwd 之外不可写、网络限制。这次的 key 剔除只能硬写在 BashTool 里，就是因为没有这样的挂点。

## 6. 对规格的偏离与理由

1. **pai 在实例容器内运行，不在宿主机运行。** spec 8B-2 写的是"优先宿主机运行 + 每题独立临时 clone"。按这个写法跑第 1 题时出了安全事件（第 7 节），用户裁决改为在容器内运行（DECISIONS-LOG 2026-10-16 M8 条目）。代价是不覆盖 Windows 路径，见 eval 报告局限第 4 条。
2. **bare 没有关闭 memory。** spec 列出的 bare 关闭项里有"记忆"，brief 给出的参数是 `--no-ext tasks,verify,subagent,skills`，我照 brief 执行。两组的 PAI_HOME 都是空目录，remember/recall 一次也没有被调用，所以对结论没有影响。
3. **主评测用 `--max_workers 4`**，brief 写的是 2。评测启动后才发现，没有中断。复评用的是 2，两次判定一致；每题都在独立容器里判定，并发数只影响速度。
4. **pai-tools 新增了 key 剔除（`43b59c2`）**，不在 spec 列出的改动范围内，理由见第 3 节。core 和 cli 都没有因此改动。
5. 名称上的小差异：spec 写的是"在容器内 `git checkout <base_commit>`"。官方实例镜像的 `/testbed` 已经处在 base（之上的 "SWE-bench" 提交）。所以没有再 checkout，改为运行前用 `git status --porcelain` 检查工作区是干净的。

## 7. 事件与 bug

### 7.1 安全事件：宿主机 yolo 运行覆盖了用户全局 Python 包

**时间线（UTC 2026-10-16，来自作废运行的 session jsonl，现存于 `D:\PaiCLI\tmp\eval\void-host\runs\<group>\astropy__astropy-14182\pai-home\sessions\`）**

| 时间 | 组 | 事件 |
|---|---|---|
| 02:16:20 | 两组 | 第一个窗口在**宿主机**按 spec 原方案启动第 1 题 `astropy-14182`：`--approval yolo`，cwd 是 `D:\PaiCLI\tmp\eval\work\<group>\astropy__astropy-14182`（独立 clone），两组并行 |
| 02:16:51 | full | bash：`cp astropy/io/ascii/rst.py "C:\Users\…\Python313\Lib\site-packages\astropy\io\ascii\rst.py"`，`fixedwidth.py` 同样处理，**没有备份**。随后在 `%TEMP%` 下用全局 python 验证 |
| 02:16:53 | bare | `pip show astropy` 定位到全局 site-packages（astropy 7.2.3） |
| 02:17:25 | bare | 先把原 `rst.py` 备份到 `/tmp/rst_orig.py`，再用 repo 版覆盖全局的 `rst.py` |
| 02:17:33、02:18:11 | full | 又两次 `cp` 覆盖全局 `rst.py` |
| 02:17:35–02:17:52 | bare | 来回覆盖、恢复（最后一次 `cp /tmp/rst_orig.py "$SP"`） |
| 运行结束后 | — | 全局 astropy 7.2.3 的 `io/ascii/rst.py`、`fixedwidth.py` 停留在被覆盖的状态。被发现后报告用户 |
| 之后 | 用户 | 执行 `pip install --force-reinstall --no-deps astropy==7.2.3` 修复；裁决：评测中 pai 一律不在宿主机运行，之前所有结果作废（已移到 `void-host\`，不参与统计） |

**根因**
1. **隔离只做了工作树这一半。** "每题独立 clone"只隔离了源码，没有隔离解释器。被测仓库没有装进 venv，`import astropy` 解析到的是用户全局安装的 7.2.3。模型要"验证修改"，最自然的做法就是把修改后的文件拷进它实际 import 的那个包里。
2. **yolo 下 bash 没有边界。** PathGuard 只拦 write/edit 的路径，bash 里的 `cp`、`>`、`sed -i` 不经过它。hitl 的 yolo 意味着不会有人逐条确认。
3. **评测方案没有把"模型会在宿主机上做任何事"当成前提。** spec 8B-2 自己写了"pai 的 bash 工具会在宿主执行，需确保工作目录隔离"，但只想到了工作目录。

**处置**：改为在实例容器内运行，宿主机只以只读方式挂载 JRE 和 jar，可写的挂载只有本题的 `/out` 和 PAI_HOME（都在 `D:\PaiCLI\tmp\eval\` 下）；容器 `--rm`；key 按用户批准的唯一方式注入，即 `-e NAME` 不带值。本窗口 60 次运行之后，扫描所有输出：key 形态字符串为 0，`API_KEY` 字样为 0。

### 7.2 bug：Windows 下 BashTool 传给 Git Bash 的命令被二次解析（`f0438fb`）

- 现象：`printf '[%s]' "a  b" 'c d'` 输出 `[a]`。凡是含引号或 glob 的命令，到 bash 那边都已经被改坏了。
- 根因：Windows 上 `ProcessBuilder` 的 argv 最终会拼成一条命令行。Java 不会转义参数内部的引号，MSYS 运行时又会再按自己的规则解析一遍引号和 glob。`bash.exe -lc <command>` 的 `<command>` 就这样被改写了。
- 修法：Windows + Git Bash 时，把命令写进临时 UTF-8 脚本，执行 `bash -l <file>`，结束后删掉脚本；Linux/macOS 的 `bash -lc` 和 cmd 回退路径不变；只读判定（`effect()`）仍然针对原始命令文本。
- 为什么 M4–M7.5 没发现：那几次 live 测试的命令都太简单（`ls`、`git status`），不含引号。这正是评测的价值：真实任务里的 `python -c "…"` 第一次就踩中了。

### 7.3 其它在评测中看到的行为

- **网络取包**：实例容器能联网，60 次运行里有 8 次用 `pip download <pkg>==<更新版本>` 拉了更新的发布版源码来对照（其中 1 次超时没拿到），是可能的上游泄漏通道。详见 eval 报告第 5 节。去掉相关的 6 题后结论不变。
- **试验文件进入补丁**：bare `sphinx-10451` 在 `tests/roots/…` 下留下了 `starargs_tmp.py`，被 `git add -A` 带进了补丁。这是 prompt 明确禁止的"新增测试文件"，按规则照常计入，没有手工剔除。
- **full 的扩展无人使用**：todo、子代理、skill、记忆工具的调用次数都是 0，verify 的内置规则在 Python 仓库上不会触发。所以本次对照没能测到 harness 功能本身的贡献，见 eval 报告第 3 节。

## 8. 坑（给后来者的）

1. 电脑上的 PowerShell 是 5.x：`Out-File -Encoding utf8NoBOM` 不可用；`-Command` 字符串里的 `$` 会被外层吃掉。脚本一律写成 `.ps1` 用 `-File` 执行，commit message 用文件传。
2. `bash` 在 PATH 上解析到的是 `C:\WINDOWS\system32\bash.exe`（WSL），Git Bash 在 `D:\Git\bin\bash.exe`。跑 `build/check-budgets.sh` 要写全路径。
3. swebench 4.x 会 import Unix 专有的 `resource` 模块，在 Windows 上无法 import，所以 harness 要放进 Linux 容器（`build/eval/harness.Dockerfile`）并挂 docker.sock。`run_evaluation` 的报告写在容器 cwd（`/eval/harness`）下，文件名是 `<model_name_or_path>.<run_id>.json`。同一个 run_id 再跑时，已有 report 的题会被跳过，复评要换 run_id。
4. 实例镜像里 `bash -l` 会自动激活 conda env `testbed`，pai 的 bash 工具本身用的就是 `bash -lc`，所以模型能直接 `python -m pytest`。容器以 root 运行。
5. HF datasets 在未认证时会往 stderr 打警告。PowerShell 5 下 `2>&1` 会把它包装成 NativeCommandError，使 `$LASTEXITCODE` 变成 1；但 python 和 harness 的退出码其实是 0，脚本以自己打印的 `harness exit 0` 为准。
6. 实例镜像单个 3.8–10.8 GB（matplotlib 最大），30 个约 140 GB。评测前要确认 Docker 所在磁盘的余量（本次运行前 C 盘 61 GB、D 盘 48 GB 空闲）。
7. Desktop Commander 的 `read_process_output` 在长任务上经常提前返回空结果；想等就用 `Start-Sleep` 加读日志文件。
8. 统计里的 "tokens in" 是每轮 input 与 cacheRead 的和，长会话里每轮都会把整段上下文重复计一次。成本主要由 cache 命中价决定（本次 94–95% 命中）。

## 9. 建议（没有做）

1. **给评测容器加出站白名单**：只放行模型 API 域名，比如加一个 egress 代理，或者用 `--network` 接一个只能访问代理的网络。这样能封住 pip 拉更新版本这条泄漏通道，也让结果和公开榜单的设定可比。
2. **产品侧给 yolo 加可选沙箱**（DECISIONS-LOG 已记为产品缺口）：例如 `--sandbox docker|none`，或者在 bash 层做路径白名单，加上"cwd 外不可写"的检测。key 剔除也应该挪到一个通用的"命令环境策略" SPI 挂点上，而不是写死在 BashTool 里。
3. **重新设计"harness 贡献"实验**：选扩展真正会生效的任务（Java/TS 仓库让 verify 触发、需要多步计划的题），每题每组至少跑 3 次再看配对差异；或者只把 subagent 这一个因素拿出来做单因素对照。
4. **verify 加 Python 默认规则**：比如有 `pyproject.toml`/`setup.py` 时，写入 `*.py` 后执行 `python -m compileall -q <file>`。这样同样的评测里 full 才会真的多出一层"确定性校验"。
5. 补丁收集时把 cwd 外的改动和明显的临时文件（`*_tmp.py`、`reproduce*.py`）单独列出来报告，不必去改它们，只是让"改测试文件"这类违规更容易看见。

## 10. 给下一阶段的输入

- 评测基础设施可以直接复用：`build/eval/run.ps1 {sample|run|preds|evaluate|report}`，镜像都已在本机，jar 每次改名固定。
- 结论：同模型下，full 和 bare 在 SWE-bench Lite 30 题上无显著差异（23 对 21，p=0.625），而且 full 的扩展在这个任务分布上**没有被模型使用**。如果要宣传"harness 的价值"，需要第 9 节第 3 条那样的实验，不能引用这次的数字。
- 安全：任何以 yolo 运行真实模型的评测或演示，都必须放在一次性容器里，并且限制网络。

## 附录

- verify 最后 10 行、`git log --oneline main..impl/M8`、`git diff --stat main..impl/M8`：见实施窗口最后一条消息（原样粘贴）。
- 评测脚本与辅助审计脚本：`build/eval/swebench.py`（含 fetch 统计）；`D:\PaiCLI\tmp\eval\m8\audit.py`（改测试文件与联网检查）、`netres.py`（取包结果）、`first_turn.py`（首轮 prompt 大小）、`scan_cp.py`（安全事件时间线）。
