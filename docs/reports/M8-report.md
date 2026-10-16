# M8 实施报告

- 分支：`impl/M8`（电脑 `D:\PaiCLI\pai-next`），未 push，未合入。
- CI：没有 remote，只在本机跑了 `mvn -B -ntp verify`，结果 BUILD SUCCESS。审查修复后共 890 个测试（修复前 887 个），1 个 skipped（`BenchTest`）。`build/check-budgets.sh` 输出 budgets OK，cli 为 1998/2000。
- 实施 session：Claude Opus 5.5，2026-10-16。中途云端会话重启，开发窗口丢失了上下文，由第二个窗口接力：第一个窗口完成了 `6bdbb47`、`f0438fb` 和评测脚本草稿；第二个窗口完成了 key 剔除、容器内运行、全量评测和两份报告。评测结果见 `docs/reports/eval-swebench.md`。
- 独立审查结论是"修后合入"。修复 commit 为 `8fa79b9`（cli）、`38fdf96`（tools）、`22acb8e` 与 `4658541`（eval），本报告和 eval 报告随后按审查意见修订（`--no-ext` 守卫、退出码、key 措辞、泄漏确认、统计措辞）。评测数据没有重跑。

## 1. 验收清单

- [x] **`pai --print` 7 种退出码测试通过；`--approval yolo --print "list files"` 真实 DeepSeek 一次通过。**
  - `MainPrintTest.every_end_reason_has_its_exit_status` 用 `@EnumSource(TerminationReasonEnum.class)` 参数化，FakeProvider 下 COMPLETED=0、MAX_TURNS=2、ABORTED=3、PROMPT_TOO_LONG=4、MAX_OUTPUT_TOKENS=5、PROVIDER_ERROR=6、HOOK_ERROR=7 各跑一例。同一个类里还有：`quiet_print_writes_only_the_final_text_and_records_the_session`（stdout 只含最终文本，session 照常写）、`print_file_and_plain_output_without_quiet`、`bad_print_options_are_usage_errors`、`no_ext_skips_extensions_by_id`。verify 日志：`Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in dev.pai.cli.MainPrintTest`。stdin 是 `/dev/null` 或非 TTY 时不会阻塞：评测的 60 次运行都是 `< /dev/null`，全部正常退出。
  - 审查修复后（`8fa79b9`）：`exitCode` 改为显式 switch，不再用 `ordinal()+1`。用法错误改为 64，原来的 2 与 MAX_TURNS 撞码。未捕获异常退出 1，这两个码都写进了 USAGE。`--print` 下强制无 UI，忽略 `PAI_INTERACTIVE=1`。`--no-ext` 遇到 `hitl`、`builtin-tools` 或未知 id 时报用法错误，而且检查在 session 文件创建之前完成。为守住 cli 预算，删掉了 `--print-file`，见第 6 节。新增和改名的测试有：`MainPrintTest.no_ext_refuses_hitl_and_builtin_tools_and_unknown_ids`、`print_ignores_an_interactive_declaration_and_never_asks`、`multi_line_prompt_and_plain_output_without_quiet`（原名 `print_file_and_plain_output_without_quiet`）；`CliSmokeTest.usage_errors_exit_with_64`（原名 `usage_errors_exit_with_two`）。修复后 MainPrintTest 共 13 个测试，全部通过。
  - 真实 DeepSeek：第一个窗口在宿主机执行 `--approval yolo --print "list files" --quiet`，exit=0（`6bdbb47`）。在此基础上，本阶段 60 次评测运行都是真实 DeepSeek 下的 `--print-file … --quiet`：58 次 exit 0，2 次 exit 2（MAX_TURNS），没有 6（PROVIDER_ERROR）。
- [x] **30 题 × 2 组跑完，`eval-swebench.md` 有完整表格与局限段；`instances.txt`、原始预测与评测 JSON 留在 `D:\PaiCLI\tmp\eval\`（报告写路径）。**
  - 60/60 次运行都有 `result.json`；harness 两组都是 `Instances submitted: 30 / completed: 30 / errors: 0 / empty patches: 0`。bare resolved 23，full resolved 21。
  - 预测文件在 `D:\PaiCLI\tmp\eval\preds\{bare,full}.jsonl`，评测 JSON 在 `D:\PaiCLI\tmp\eval\harness\pai-next-{bare,full}.{bare,full}.json`，session 在 `D:\PaiCLI\tmp\eval\home\<group>\<id>\`。完整路径清单见 eval 报告第 6 节。
- [x] **脚本可复现：换 seed 能重抽；同清单重跑评测步骤能得到相同 resolved 判定。**
  - 用默认 seed 重抽，输出与 `build/eval/instances.txt` 逐行相同（`default identical: True`）；用 `--seed 1` 重抽得到另一份 30 题清单，与原清单只有 1 题重合（`D:\PaiCLI\tmp\eval\m8\instances-seed1.txt`）。
  - 复评：用 run id `bare-r2` / `full-r2` 对同一份预测重新评测，两组的 resolved 集合与第一次相同（`bare first=23 second=23 identical=True`、`full first=21 second=21 identical=True`）。
- [x] **cli ≤ 2000；core 零改动；报告 `docs/reports/M8-report.md`。** 审查修复后 cli 仍为 1998。`git diff --stat main..impl/M8 -- pai-core` 为空。本阶段对产品代码的改动只在 pai-cli（`6bdbb47`、`8fa79b9`）和 pai-tools（`f0438fb`、`43b59c2`、`38fdf96`）。

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
- `43b59c2` pai-tools：BashTool 执行命令时，从子进程环境里去掉 `PAI_*_API_KEY` 和内置预设的 key 变量（`DEEPSEEK_API_KEY` 等），并加了测试 `BashToolEnvTest`。这项措施只**降低误打印（`env`/`printenv`）的风险**，**防不住主动读取**：命令执行 `cat /proc/1/environ` 或 `/proc/$PPID/environ` 照样能读到 agent 进程的环境。本次 60 次运行共 659 次 bash 调用，逐条扫描，没有这类调用。这项不在 spec 列出的范围内，见第 6 节。同一个 commit 里还带了主控放在工作区的 `docs/DECISIONS-LOG.md` 三条 M8 记录。
- `b80b92f` 加 `build/eval/` 全套脚本；`bab49a9` 给 report 加了 fetch 统计和格式调整。`build/eval/` 不计预算。
- 审查修复：
  - `8fa79b9` cli：`--no-ext` 守卫、退出码 64/1、显式 switch、`--print` 无 UI、删 `--print-file`。
  - `38fdf96` tools：剔除集合补上用户 `~/.pai/providers.json` 与 `models.json` 里自定义的全部 `apiKeyEnv` 名。由 `BuiltinToolsExtension` 从扩展上下文的 `paiHome()` 读取，不碰 core，也不碰 cli。测试为 `BashToolEnvTest.custom_api_key_env_names_from_providers_and_models_files_are_removed`。
  - `22acb8e`、`4658541` eval：固定数据集 revision；result.json 记录 jar/镜像/参数；区分 `LAUNCH_FAILED` 与 TIMEOUT；退出码映射同步；argv 使用 UTF-8 locale。

## 4. 签名异议

无。

## 5. SPI 漏洞

没有发现新的 SPI 漏洞。有一个产品层面的缺口，评测正好撞上：hitl 的 yolo 模式下，bash 没有任何进程级边界。PathGuard 只管 write/edit，扩展 SPI 也没有给 bash 提供"运行环境策略"的挂点，比如 env 过滤、cwd 之外不可写、网络限制。这次的 key 剔除只能硬写在 BashTool 里，就是因为没有这样的挂点。

另一个缺口本阶段只记录、不修：**MCP stdio 子进程继承 pai 的完整环境**（`StdioTransportImpl.java:61-63`：`new ProcessBuilder(...)` 后只 `putAll(cfg.env())`），provider key 也在其中。一个 MCP server 能读到所有 key，BashTool 的剔除对它不起作用。修法应当与上面的"命令环境策略"挂点一起设计。

## 6. 对规格的偏离与理由

1. **pai 在实例容器内运行，不在宿主机运行。** spec 8B-2 写的是"优先宿主机运行 + 每题独立临时 clone"。按这个写法跑第 1 题时出了安全事件（第 7 节），用户裁决改为在容器内运行（DECISIONS-LOG 2026-10-16 M8 条目）。代价是不覆盖 Windows 路径，见 eval 报告局限第 4 条。
2. **bare 没有关闭 memory。** spec 列出的 bare 关闭项里有"记忆"，brief 给出的参数是 `--no-ext tasks,verify,subagent,skills`，我照 brief 执行。两组的 PAI_HOME 都是空目录，remember/recall 一次也没有被调用，所以对结论没有影响。
3. **主评测用 `--max_workers 4`**，brief 写的是 2。评测启动后才发现，没有中断。复评用的是 2，两次判定一致；每题都在独立容器里判定，并发数只影响速度。
4. **pai-tools 新增了 key 剔除（`43b59c2`）**，不在 spec 列出的改动范围内，理由见第 3 节。core 和 cli 都没有因此改动。
5. 名称上的小差异：spec 写的是"在容器内 `git checkout <base_commit>`"。官方实例镜像的 `/testbed` 已经处在 base（之上的 "SWE-bench" 提交）。所以没有再 checkout，改为运行前用 `git status --porcelain` 检查工作区是干净的。
6. **审查修复中删掉了 `--print-file`**（spec 8A 原有）。修复 P1/P2 后 cli 到了 2016 行，按主控指示优先删 `--print-file`。替代方式是 `-p "$(cat file)"`：评测脚本已经改用这种方式，并在容器里设 `LC_ALL=C.UTF-8`，否则非 ASCII 字符会变成 `?`。M8 那 60 次运行用的仍是 `--print-file`，数据有效。另外，把 `start()` 里两段 try/catch 合并为一段，USAGE 压缩了 2 行，又把 3 个只有一句话的 javadoc 收成单行，内容不变（`Main` 类注释，`Repl` 的两个内部类型注释）。最终 1998 行。没有删测试，也没有改预算脚本：`print_file_and_plain_output_without_quiet` 改成了多行 `-p` 的测试，`--print-file` 现在断言为用法错误。
7. **复审后改了 pai-model 的 `FakeProviderImpl.echo()`**（+20 行，pai-model 现为 2683/4000），让它支持 `!tool <name> <json>`。这样 `print_ignores_an_interactive_declaration_and_never_asks` 能走真实入口：在 `PAI_INTERACTIVE=1` 和默认 auto-read 模式下用 `--print` 发起一次 `write`，断言该调用以 "nobody answered" 被 block（不是 "user denied"）、文件没有写出、退出 0，并加了 `@Timeout(60)`。cli 和 core 都没有改动。

## 7. 事件与 bug

### 7.1 安全事件：宿主机 yolo 运行覆盖了用户全局 Python 包

**时间线（UTC 2026-10-16，来自作废运行的 session jsonl，现存于 `D:\PaiCLI\tmp\eval\void-host\runs\<group>\astropy__astropy-14182\pai-home\sessions\`）**

| 时间 | 组 | 事件 |
|---|---|---|
| 02:16:20 | 两组 | 第一个窗口在**宿主机**按 spec 原方案启动第 1 题 `astropy-14182`：`--approval yolo`，cwd 是 `D:\PaiCLI\tmp\eval\work\<group>\astropy__astropy-14182`（独立 clone），两组并行 |
| 02:16:51 | full | bash：`cp astropy/io/ascii/rst.py "C:\Users\…\Python313\Lib\site-packages\astropy\io\ascii\rst.py"`，`fixedwidth.py` 同样处理，**没有备份**。随后在 `%TEMP%` 下用全局 python 验证 |
| 02:16:53 | bare | `pip show astropy` 的结果返回，定位到全局 site-packages（astropy 7.2.3） |
| 02:16:55 | bare | 第一次对全局 site-packages 发起 bash 调用（`SP="…\site-packages\astropy\io\ascii\rst.py"; …`），原话是 "temporarily copy my new rst.py into the installed package and test, then restore" |
| 02:17:25 | bare | 先把原 `rst.py` 备份到 `/tmp/rst_orig.py`，再用 repo 版覆盖全局的 `rst.py` |
| 02:17:33、02:18:11 | full | 又两次 `cp` 覆盖全局 `rst.py` |
| 02:17:35–02:17:52 | bare | 来回覆盖、恢复（最后一次 `cp /tmp/rst_orig.py "$SP"`） |
| 运行结束后 | — | 全局 astropy 7.2.3 的 `io/ascii/rst.py`、`fixedwidth.py` 停留在被覆盖的状态。被发现后报告用户 |
| 之后 | 用户 | 执行 `pip install --force-reinstall --no-deps astropy==7.2.3` 修复；裁决：评测中 pai 一律不在宿主机运行，之前所有结果作废（已移到 `void-host\`，不参与统计） |

**根因**
1. **隔离只做了工作树这一半。** "每题独立 clone"只隔离了源码，没有隔离解释器。被测仓库没有装进 venv，`import astropy` 解析到的是用户全局安装的 7.2.3。模型要"验证修改"，最自然的做法就是把修改后的文件拷进它实际 import 的那个包里。
2. **yolo 下 bash 没有边界。** PathGuard 只拦 write/edit 的路径，bash 里的 `cp`、`>`、`sed -i` 不经过它。hitl 的 yolo 意味着不会有人逐条确认。
3. **评测方案没有把"模型会在宿主机上做任何事"当成前提。** spec 8B-2 自己写了"pai 的 bash 工具会在宿主执行，需确保工作目录隔离"，但只想到了工作目录。

**处置**：改为在实例容器内运行，宿主机只以只读方式挂载 JRE 和 jar，可写的挂载只有本题的 `/out` 和 PAI_HOME（都在 `D:\PaiCLI\tmp\eval\` 下）；容器 `--rm`；key 按用户批准的唯一方式注入，即 `-e NAME` 不带值。BashTool 的 key 剔除**只降低误打印（`env`/`printenv`）的风险**，**防不住主动读取**：`/proc/1/environ`、`/proc/$PPID/environ` 都能读到 key。本次 60 次运行的 659 次 bash 调用里没有这类调用；扫描所有输出，key 形态字符串为 0，`API_KEY` 字样为 0。真正的隔离要等下一轮：由宿主侧代理注入 Authorization，key 不进容器（第 9 节第 1 条）。

### 7.2 bug：Windows 下 BashTool 传给 Git Bash 的命令被二次解析（`f0438fb`）

- 现象：`printf '[%s]' "a  b" 'c d'` 输出 `[a]`。凡是含引号或 glob 的命令，到 bash 那边都已经被改坏了。
- 根因：Windows 上 `ProcessBuilder` 的 argv 最终会拼成一条命令行。Java 不会转义参数内部的引号，MSYS 运行时又会再按自己的规则解析一遍引号和 glob。`bash.exe -lc <command>` 的 `<command>` 就这样被改写了。
- 修法：Windows + Git Bash 时，把命令写进临时 UTF-8 脚本，执行 `bash -l <file>`，结束后删掉脚本；Linux/macOS 的 `bash -lc` 和 cmd 回退路径不变；只读判定（`effect()`）仍然针对原始命令文本。
- 为什么 M4–M7.5 没发现：那几次 live 测试的命令都太简单（`ls`、`git status`），不含引号。这正是评测的价值：真实任务里的 `python -c "…"` 第一次就踩中了。

### 7.3 其它在评测中看到的行为

- **网络取包，已确认至少 3 题照抄上游修复（共 4 次运行：django-14667×2、sphinx-10451 bare、sympy-20049 full）**：实例容器能联网，60 次运行里有 8 次用 `pip download <pkg>==<更新版本>` 拉取了更新的发布版源码（其中 1 次超时没拿到）。确认照抄的：
  - `django-14667`：两组补丁都与 gold 100% 相同。session 第 13 轮原话是 "Found the upstream fix… apply exactly this"，并读了 FAIL_TO_PASS 对应的测试。
  - `sphinx-10451`：bare 把 Sphinx 5.1.0 的 `typehints.py` 整份搬了过来，第 38 轮自己 diff 后输出 `IDENTICAL`。这题是 bare 独赢的 3 题之一，赢在泄漏上；full 同题没有取包，判 no。
  - `sympy-20049` full：session 第 24 轮 "This is the upstream implementation"，第 28 轮 "adopt upstream 1.7's `vel` implementation"，第 29–31 轮照上游改写了 `vel`/`acc`。
  - 不是每次取包都照抄：审查抽查 `sympy-20049` bare 与 gold 重合 72%，`django-15814` bare 0%，`sphinx-8506` full 20%。
  - 去掉 6 道取过包的题后 b=2、c=1、p=1.0，"无显著差异"不变；**绝对分数作废，不可引用**。fetch 统计只是下界。详见 eval 报告第 5 节。
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
9. SWE-bench 实例镜像默认是 POSIX locale，JVM 按 ASCII 解码 argv：`-p "$(cat prompt.txt)"` 里的中文会全部变成 `?`。run.sh 必须先 `export LC_ALL=C.UTF-8`。这是删掉 `--print-file` 后，在 fake provider 冒烟时发现的。
10. 改 cli 时，预算按 spotless 格式化之后的非空行数计算，所以要先 `mvn spotless:apply -pl pai-cli` 再数；格式化器会把长条件拆成多行。
11. `Main.start()` 用一个 `catch (IllegalArgumentException)` 包住了 catalog 加载、resume 和整个 `buildRepl`（这样 `--no-ext` 检查不用额外占行）。副作用是扩展 install 时抛出的 IAE 也会被当成用法错误，退出 64，而不是以崩溃退出 1。本阶段不改，以后如果把扩展加载失败细分出来，这里要一起改。
12. `--provider fake` 的 echo 支持 `!tool <name> <json>`：回复这一次工具调用，下一轮再回显工具结果文本（`FakeProviderImpl.TOOL_PREFIX`）。这是为了从真实入口测 `--print` 下审批会被 block，在 pai-model 加的。

## 9. 建议（没有做）

1. **给评测容器加出站白名单，同时由宿主侧代理注入 key**：容器用 `--network` 接一个只能访问宿主代理的网络，代理只放行模型 API 域名，并由代理加上 `Authorization` 头。这样 key 不进容器，`/proc/*/environ` 也读不到；pip 拉更新版本这条已经确认的泄漏通道被封住，结果也能和公开榜单的设定相比。这两件事要一起做。
2. **产品侧给 yolo 加可选沙箱**（DECISIONS-LOG 已记为产品缺口）：例如 `--sandbox docker|none`，或者在 bash 层做路径白名单，加上"cwd 外不可写"的检测。key 剔除也应该挪到一个通用的"命令环境策略" SPI 挂点上，而不是写死在 BashTool 里。
3. **重新设计"harness 贡献"实验**：选扩展真正会生效的任务（Java/TS 仓库让 verify 触发、需要多步计划的题），每题每组至少跑 3 次再看配对差异；或者只把 subagent 这一个因素拿出来做单因素对照。
4. **verify 加 Python 默认规则**：比如有 `pyproject.toml`/`setup.py` 时，写入 `*.py` 后执行 `python -m compileall -q <file>`。这样同样的评测里 full 才会真的多出一层"确定性校验"。
5. 补丁收集时把 cwd 外的改动和明显的临时文件（`*_tmp.py`、`reproduce*.py`）单独列出来报告，不必去改它们，只是让"改测试文件"这类违规更容易看见。

## 10. 给下一阶段的输入

- 评测基础设施可以直接复用：`build/eval/run.ps1 {sample|run|preds|evaluate|report}`，镜像都已在本机，jar 每次改名固定。
- 结论：同模型下 full 与 bare 无显著差异（23 对 21，p=0.625；去掉取包题后 p=1.0）；full 的扩展未被使用；绝对分数因上游泄漏作废。下一轮需要三样东西：出站白名单（加宿主侧 key 注入）、扩展会生效的任务集、每题多次运行。在这之前，不能引用这次的任何数字来说明 harness 的价值或 pai 的水平。
- 安全：任何以 yolo 运行真实模型的评测或演示，都必须放在一次性容器里，并且限制网络。

## 附录

- verify 最后 10 行、`git log --oneline main..impl/M8`、`git diff --stat main..impl/M8`：见实施窗口最后一条消息（原样粘贴）。
- 评测脚本与辅助审计脚本：`build/eval/swebench.py`（含 fetch 统计）；`D:\PaiCLI\tmp\eval\m8\audit.py`（改测试文件与联网检查）、`netres.py`（取包结果）、`first_turn.py`（首轮 prompt 大小）、`scan_cp.py`（安全事件时间线）、`envscan.py`（659 次 bash 调用里有没有读取环境的命令）、`gold_overlap.py`（运行补丁与 gold 的重合度）、`smoke.ps1`（新脚本加 fake provider 的冒烟，产物在 `D:\PaiCLI\tmp\eval\smoke\`）。
