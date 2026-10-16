# 面试追问清单与参考答案（pai-next）

> 用途：面试前过一遍。每条给出"面试官会怎么问 → 一句话答 → 展开时讲哪个真实决定/数字 → 坦白的局限"。答案全部来自仓库里的 ADR、DECISIONS-LOG 和阶段报告，不要说仓库里没有的东西。
> 原则：先一句话结论，再讲一个具体决定和它的理由，最后主动说局限。被问到不知道的，说"这个我没做，我知道的边界是……"。

## 0. 三十秒介绍

pai-next 是一个 Java 21 的终端 coding agent，架构对标开源的 pi（badlogic/pi-mono）。它是我第一版 PaiCLI 的重做：第一版 23 期迭代后 `Main.java` 170KB、到处是兼容层、只支持 OpenAI 格式；第二版重做成**极小内核 + 扩展 SPI**：内核约 5k 行（预算 5.5k，脚本强制），只有 provider 中立的消息类型、事件驱动的 agent loop、工具契约、JSONL session 树和扩展 SPI 五样东西；审批、记忆、MCP、任务清单、验证钩子、子代理全是扩展，扩展之间不能互相依赖（ArchUnit 强制）。11 个模块、约 18.8k 行主代码、874 个测试，CI 在 Linux 和 Windows 上跑，用 DeepSeek 真实验证过。

## 1. 架构与工程化

**Q：为什么重做而不是重构？**
一句话：旧骨架的"兼容层"已经是设计本身，改不动。展开：旧仓库 `ToolRegistry` 97KB，Legacy/Adapter/Compat 命名遍地，消息类型和 OpenAI wire format 绑死，加 Anthropic 就要再加一层 Adapter。重做时把旧仓库当"零件库"：只允许读后重写，不允许复制文件（`docs/00-architecture.md` §7 列了可搬运清单）。局限：重做成本高，花了 8 个阶段；如果旧项目有用户就不能这么干。

**Q：怎么防止第二版又变成屎山？**
一句话：用机器检查替代自觉。展开：(1) 行数预算脚本 `build/check-budgets.sh`，core ≤5500、cli ≤2000、每个扩展 ≤2500、单文件 ≤400 非空行、源码里 TODO=0，CI 上跑；(2) ArchUnit：core 不 import tui/cli、ext 互不依赖、枚举带 `Enum` 后缀、服务型实现带 `Impl`、核心层无中文字面量、`ContextStage` 实现类只能在 core 内；(3) Spotless 用阿里 p3c 模板；(4) 每个阶段一份规格书，公开签名是合同，实施不能改，有异议写报告。最有说服力的证据：M7 时 core 到 4992/5000，实施方靠删 Javadoc 挤出来，我把预算提到 5500 并记录"不得再为预算删 API/压注释"——预算的目的是暴露增长，不是逼人压缩。

**Q：五个地基决定是什么？哪个最后悔？**
ADR 0001 provider 中立消息（sealed `Message`/`ContentBlock`，Thinking 签名按 provider+model 回放）；0002 工具契约（`Tool{definition, effect, execute}`）；0003 事件驱动 loop（有限枚举的钩子集，不递出内部对象）；0004 JSONL session 树（非破坏，fork 是新 leaf）；0005 扩展 SPI（`ExtensionContext` 一组能力方法）。每个 ADR 都有修订记录，一共 12 条。最"后悔"的是 0002 在 M7 加 `concurrencySafe(input)` 时默认实现写成 `effect(input)==READ_ONLY`，忽略了工具已覆写的 `concurrencySafe()==false`，会让有状态的只读工具（任务清单、MCP 资源）升级后悄悄变成可并行——审查抓出来后改成 `concurrencySafe() && effectOf(...)==READ_ONLY`。这是我写 ADR 时的错，不是实施的错。

**Q：你的开发流程？**
每个阶段：主控写规格 → 一个实施窗口按规格做 → 一个**独立**审查窗口（先不读实施报告，自己读规格和 diff，实验要写明参数可复现）→ 裁决（必修/一起修/记录）→ 修复 → 复审 → 合入打 tag。约 125 条裁决都在 `DECISIONS-LOG.md`，只追加不改。审查在 8 个阶段里抓到约 30 个真 bug，其中 3 个是默认配置下不经审批执行任意代码级别的（见 §4）。

## 2. Agent loop 与编排（简历第 1、2 条）

**Q：loop 是怎么跑的？一轮做什么？**
`AgentLoop.run` 每轮固定顺序：`transformContext` 钩子链 → `ContextStage` 管线（微压缩、全量摘要两段）→ 预算检查 → provider 流式请求 → 工具执行（`ToolRunner` 按 `concurrencySafe(input)` 分组：连续安全调用并行，不安全串行）→ `steeringMessages` → 终止判定。结束时 `AgentEnd.reason` 是 7 种之一：COMPLETED / MAX_TURNS / ABORTED / PROMPT_TOO_LONG / MAX_OUTPUT_TOKENS / PROVIDER_ERROR / HOOK_ERROR，每种各有一个 FakeProvider 测试。所有消费者（TUI、无头输出、扩展）看同一条 `AgentEvent` 事件流。

**Q：第一版有 DAG Planner/Worker/Reviewer，第二版为什么拿掉？没有 DAG 怎么编排？**
一句话：编排从框架搬进模型，框架只留四个薄机制。展开，旧 DAG 的每个职责的新归属：Planner 拆任务 → 模型自己写**任务清单**（`todo_write` 工具，状态存 session `CustomEntry`，`prepareRequest` 注入 `<tasks>` 段，规则"同一时间只有一项 in_progress"）；Worker 并发 → **子代理**（`spawn_agent`，同时获得上下文隔离）；确定性 verification + Reviewer → **验证钩子**（`.pai/verify.json`，写文件后自动跑 `mvn -q compile`，非 0 的输出尾部回灌给模型，模型自己修）+ reviewer 类型子代理；Plan 审阅交互 → **PLAN 只读模式**（Shift+Tab 切换，模型只能读，输出方案后用户切回执行）。资源冲突预检是 DAG 唯一保留的调度思想，变成子代理之间的 per-path 写锁。为什么这样更好：旧 DAG 的 schema 修复、UNKNOWN 恢复、at-most-once 追踪都是在替模型做模型本来会做的判断，而且模型每进步一次这些代码就更多余。局限：没有全局依赖图，长任务的"回头"靠 session 树 `/fork` 回退，不是自动的。

**Q：工具并行怎么判定安全？`bash ls` 和 `bash rm` 怎么区分？**
`Tool.effect()` 是静态上限（READ_ONLY/WRITE_FS/EXECUTE/NETWORK/UNKNOWN），`effect(input)` 按本次输入细化，**只能更安全不能更危险**（返回更危险的取两者中更危险者，抛异常取声明值——fail-closed）。bash 的判定：`ShellCommands` 词法解析（引号、转义、`; && || | &`、`$()`、反引号、重定向），再对照只读表资源文件 `read-only-commands.txt`（命令名 + 禁用选项，如 `find` 禁 `-delete/-exec`，`git` 禁 `-c/--output`）。规则：含引号/反斜杠/`$`/**未加引号的 glob** 一律 EXECUTE。glob 这条是审查抓出来的 P0：cwd 里放一个名为 `--pre=sh` 的文件，`rg TODO *` 展开后就变成了 `rg --pre=sh`，默认模式免审 RCE。95 条表驱动样例守着。

**Q：MAX_OUTPUT_TOKENS 怎么处理？**
`StopReason=LENGTH` 且工具调用被截断时，追加一条 "continue" user message 重试，最多 2 次（连续计数，非截断回复清零），超过以该原因结束。

## 3. 上下文、压缩与记忆（简历第 3 条——变化最大）

**Q：上下文怎么管？**
三层。(1) **微压缩**：确定性、零模型调用，把最近 N 轮之前超过 2000 字的工具结果替换为占位 `[tool result trimmed by pai: read, 2827 chars, 28 lines; call id …]`，保留调用配对；它是**请求投影**——每次请求重算，不写 session，不改保留的上下文。两档（keepRecent 4 → 1）加滞回。(2) **全量摘要**：模型按六节模板（Task / Done / Files touched / Decisions / Open issues / Next step）生成，`SummaryValidator` 校验缺节/漏文件则重试一次，仍不合格就不压缩；写 `CompactionEntry`。(3) **压缩后文件恢复**：从 `Files touched` 取最近 ≤5 个文件的当前内容作为 `<restored-files>` 回灌，总量 min(25k, 剩余空间/2)——解决"压缩后模型要重读才能 edit"（edit 需逐字 oldText）。数字：8k 窗口同一任务，全量压缩 M5 4 次 → M6 0 次；全量摘要 40382 → 2085 token；微压缩 200 条消息 0.13 ms。

**Q：为什么微压缩放 `prepareRequest` 不放 `transformContext`？**
这是实施方的偏离，审查独立核实后我接受了：loop 会把 `transformContext` 的返回值存成上下文并发 `ContextCompacted`，而全量摘要的前置条件是"上下文与 session 投影逐条 equals"，带占位的上下文对不上，之后的摘要全被跳过；放 `prepareRequest` 只改本次请求。M7 又把它提升为 loop 可见的 `ContextStage`，事件由 loop 独家发出。能讲出这条说明你知道"投影"和"状态"的区别。

**Q：估算 token 怎么做？踩过什么坑？**
chars/4 估算，用上一条回复的 usage 校准（取 max）。坑有两个都被审查抓到：M6 时投影估算用"原估算 − 节省量"，但微压缩生效后第二次请求的 usage 已经是裁剪后的大小，再减就是重复扣，等于把校准丢了；改成记住上次发出时被裁的 toolCallId 集合，只扣新裁部分。M7 时摘要保留的 assistant 消息其 usage 是压缩前的大小，让"压缩后仍超阈值计失败"的熔断误触；改成把这些消息标 `staleUsage` 按字符估算直到新回复。

**Q：记忆系统是怎么做的？和第一版的检索有什么不同？**
一句话：第一版是"检索式"（按查询动态注入），第二版改成 **Claude Code 式的文件记忆**：`~/.pai/memory/<global|cwd-hash>/MEMORY.md`，每条一行 `- [id] text #tag`，加可选主题文件；会话开始**固定**注入前 60 行/4000 字，整个会话不变（不破 prompt cache）；主题文件只经 `recall` 工具按需读。写入来源：显式 `remember` 工具，以及**压缩 → 记忆候选**——全量摘要的 `## Decisions` 节每行一条候选，会话结束逐条 y/n 确认，拒绝的哈希写进 session 让 resume 后不再问，无 UI 不保存。不做：embedding、RRF、自动抽取。为什么放弃检索式：个人 coding agent 的记忆几百条量级，动态注入每轮改变 system prompt 破缓存，而且"什么值得记"模型在压缩时已经判断过了，不需要再做一套抽取。坦白：旧简历里的"记忆检索"这条相当于彻底换了做法，如果被问为什么，答案是上面这段。

**Q：压缩失败怎么办？**
连续 3 次失败（含"摘要成功但压缩后仍超阈值"）熔断：停止自动压缩、notify，手动 `/compact` 仍可用，成功清零。provider 返回 context overflow 时 force 压缩重试一次。

## 4. 安全与审批（harness）

**Q：审批模型？**
ASK / AUTO_READ（默认）/ PLAN / YOLO（只能显式参数）。Guard（PathGuard 真实路径、CommandGuard 危险命令表）先于审批且**不可批准**。AUTO_READ 下只读工具与只读 bash 免审，但 bash 的每个非选项词都做真实路径解析（symlink 指向 cwd 外 → 转审批），`git` 只在受信任项目免审（`.git/config` 的 `core.fsmonitor` 能让 `git status` 执行程序）。审批 `A` = 持久化 allow 规则到 `~/.pai/ext/hitl/permissions.json`（bash 按首词 `mvn *`）；deny 规则不可批准，按词序列匹配（剥 env/command/路径前缀），解析不干净（引号、`$`、`sh -c`）且存在 deny 规则时禁止任何自动放行。项目级 `.pai/permissions.json` 需信任且不能放宽用户级 deny。项目信任：首次在含 `.env/.pai/mcp.json/.pai/extensions/.pai/verify.json/.pai/permissions.json` 的目录启动时询问。

**Q：审查抓到过哪些安全问题？**（主动讲，这是项目最有分量的部分）
- M4：管道下从 stdin 读审批（非 TTY 时能被输入流伪造）；PathGuard 悬空 symlink fail-open；审批提示里的控制字符/Unicode 方向字符伪装。
- M5：PLAN 只读白名单被引号拼接绕过（可静默删家目录）；PLAN 下白名单 bash 免审读 cwd 外文件；验证钩子"写构建脚本 → 自动执行"的跨轮提权（修法：写入命中构建定义文件则本轮跳过并粘滞标记）。
- M7：glob 展开注入选项（上面讲过，默认模式 RCE）；只读 bash 经 symlink 读 cwd 外；git 执行仓库配置程序；deny 规则被双空格/`env`/`/usr/bin/git` 绕过；cmd 回退下 `git pu^sh` 绕过。
- M7.5：后台子代理在父空闲时审批抛异常，且 `afterToolCall` 链不隔离异常，写锁泄漏到进程退出。
每条都有修法和测试。局限：没有平台沙箱（seatbelt/bwrap），`bash` 不在 PathGuard 范围内（文档写明），这是审批模型能做到的边界。

**Q：凭据怎么管？**
只从 `.env` 读进进程环境，`ExtensionContext.option()` 明确不得用于读凭据，扩展不能自建 provider；CI 跑 gitleaks；所有 live 测试脚本运行后统计日志里 key 形态字符串为 0。M1 审查抓过一个：`.env` 里改写 base URL 会把 key 发到别的域名。

## 5. 扩展与 MCP（简历第 4 条）

**Q：扩展 SPI 长什么样？怎么防止它变成第二个内核？**
`Extension{id, install(ExtensionContext)}`，`ExtensionContext` 是一组能力方法：registerTool/Command/Provider、addHooks、on(event)、configDir、ui、notify、projectTrusted、option、tool(name)、appendEntry/latestEntry、subLoop。钩子是有限枚举（beforeToolCall / afterToolCall / transformContext / steeringMessages / prepareRequest），扩展只看 `LoopState` 只读视图，不能注册 `ContextStage`（避免第二套钩子）。SPI 一共修订 4 次，每次都是实施方遇到做不出来的东西 → 写报告 → 我裁决加最小方法并记 ADR；拒绝过两次绕法（M5 "从最后一次 todo_write 推导清单"、M7.5 "cli 直接依赖 ext 手工构造"）。加载：classpath 上的 ServiceLoader 先于 `~/.pai/extensions/*.jar`，项目目录的 jar 需信任。

**Q：MCP 怎么做的？**
独立客户端，stdio + Streamable HTTP 两种 transport，MCP 工具实现同一个 `Tool` 接口、`effect()=UNKNOWN`（所以在 PLAN/explore 里被 fail-closed 拦住），core 零改动。配置 `.pai/mcp.json` 触发项目信任询问。

**Q：子代理怎么做隔离？**
`ExtensionContext.subLoop(config, offered)`：宿主以当前 provider、过滤后的工具注册表、钩子链中**只有** `beforeToolCall/afterToolCall` 的转发构造独立 `AgentLoop`——审批照常走父 UI，但 skill 索引、记忆、任务清单、恢复消息这些挂在 `prepareRequest` 上的注入都不进子代理（explore 从空上下文开始，省 token）。子 session 是侧链文件 `sessions/<cwd>/subagents/<parent>/sa-*.jsonl`，父只记一条 `CustomEntry{runId, type, task, resultSummary ≤500 字, usage}`。验收：父 session jsonl 里 grep 不到子代理读过的任何文件正文（live 实证：9 个文件的标记父 0 次、侧链 9 次）。禁止嵌套、并发 ≤4、父取消传递、子代理间写同一真实路径串行。局限：后台子代理一律只读（父空闲时终端不能被第二个线程读），锁在 preflight 取、执行后释放（SPI 缺"执行前一刻"钩子），TUI 还不渲染子代理每 turn 进度。

## 6. 性能与测试

**Q：性能怎么样？测过什么？**
`build/bench.ps1`（fake provider，Windows 11，Java 21）：冷启动到提示符 1031 ms（JVM 为主）；1000 条 entry 的 session `--resume` 858 ms，其中 store 加载 17.5 ms；一次 `read` 工具的 loop 开销 1.63 ms（事件分发 + hooks + schema 校验 + 执行，相对模型秒级延迟可忽略）；微压缩 200 条 0.127 ms；全量摘要 40382 → 2085 token；侧栏查找增量解析后 4.35 → 1.20 ms。不设门槛只建数字——目的是被问到时有据可答。坦白：没做 JIT 预热/CDS/GraalVM，启动 1 秒是 JVM 的代价。

**Q：测试策略？**
874 个测试默认全开：provider 用 fixtures 录制回放（`build/RecordFixtures.java`），loop 用 `FakeProviderImpl` 脚本回复，有 key 的 live 打 `@Tag("live")`。每个阶段验收清单必须附证据（命令输出、jsonl 片段、脱敏请求体），审查员的实验要写明参数能复现。CI：Ubuntu verify + 预算脚本、Windows verify（抓过 8.3 短路径暴露的测试假设）、gitleaks。

## 8. 评测（M8）——被问"你怎么证明它有用"时

**Q：有没有跑过基准？分数多少？**
一句话：跑过 SWE-bench Lite 30 题的**同模型对照**（DeepSeek，full 扩展全开 vs bare 只有四个内置工具），结论是**无显著差异**，而且绝对分数作废——这两件事我都能解释。展开：bare 23/30、full 21/30，McNemar p=0.625；结果不一致只有 4 题。原因一：full 多出的扩展（任务清单、子代理、skill、记忆）在 60 次运行里 **0 次被模型调用**，verify 默认规则只认 Maven/npm/Cargo/Go 而 9 个仓库全是 Python，所以对照实际测的是"多 750 token 工具说明的扰动"。原因二：容器能联网，审查逐题审读确认至少 2 题模型 `pip download` 了更新版本直接照抄上游修复（一题补丁与 gold 100% 相同，模型原话 "Found the upstream fix… apply exactly this"），所以 70%+ 的绝对分数不可引用。
**为什么这反而是加分项**：说明我知道 agent 评测的三个坑——模型与脚手架不可分、泄漏通道、单次运行的随机性——并且知道下一轮怎么设计：出站白名单 + 宿主代理注入 key、让扩展真正生效的任务集（Java/TS 仓库、多步任务）、每题 ≥3 次。面试官要的是这个判断力，不是一个数字。

**Q：评测过程发现了什么？**
三个真问题，都比分数值钱：(1) Windows 下 BashTool 把命令当 argv 传 `bash.exe -lc`，MSYS 二次解析把 `python -c '...'` 全弄坏，模型 40 轮都在和引号较劲——M4–M7.5 的 live 命令太简单没踩到，第一道真实任务就踩了；(2) 在宿主机以 yolo 跑第一题时模型把工作副本文件 `cp` 进了我全局 Python 的 site-packages——PathGuard 只管 write/edit，bash 在宿主无边界；处置是评测一律在实例容器内跑、宿主只挂只读 JRE/jar，产品侧记下 `--sandbox` 缺口；(3) 容器里 yolo 下模型一句 `env` 就能把 key 打进 transcript，加了子进程环境剔除，但审查指出 `/proc/1/environ` 仍可读，正确解法是宿主代理注入 Authorization 让 key 根本不进容器。

## 7. 坦白清单（被问到就承认）

- Anthropic provider 只验了协议（fixtures），没有 key 没跑 live。
- 没有平台沙箱；bash 不在 PathGuard 范围。
- 记忆没有检索（embedding/RRF），是有意为之。
- 子代理：后台只读、锁的已知限制、TUI 进度未渲染。
- `bench.sh` 没在真实 bash+Java 环境跑过，数字来自 `bench.ps1`。
- cli 预算 1933/2000，再加功能要先拆。
- 恢复消息排在最新 prompt 后可能被当最新指令，需要 meta 消息类型（记录未做）。
- SWE-bench 对照结果为无显著差异且绝对分数因泄漏作废；不能引用任何数字说明 harness 价值。
- 整个项目是我做主控（写规格、裁决、审报告），实施和审查用了 AI 开发窗口；要讲清楚的是**决定和理由**，不是手敲了多少行。
