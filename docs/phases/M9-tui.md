# M9 规格书：TUI 升级（Claude Code 形态：滚动输出 + 固定底栏 + 着色与折叠）

> 前置：M8.5 通过（tag M8.5）。本阶段只改 `pai-tui`（预算 1500 → **3000**，主控已裁决），**core 零改动、cli 零改动**（cli 1998/2000 封顶；Renderer 的构造与注入已在 `RendererFactory`，若 cli 必须改一行，先停下汇报）。目标是用户日常使用时"看得清、看得舒服"，不是做全屏框架（pi 那档明确不做，见 DECISIONS-LOG）。
> 参考（只看思路不搬代码）：pi 的 `packages/tui`（组件划分、Markdown 渲染、工具卡片折叠策略）、Claude Code 的公开截图/描述（滚动输出 + 底部输入与状态栏、工具输出默认折叠、diff 着色）、旧 PaiCLI `D:\PaiCLI\paicli` 里的 TUI 代码（读后理解哪些做法好用）。

## 来自前阶段的输入
- `Renderer` 接口（readLine/println/status/onInterrupt/sidebar）与 `EventView` 已把事件 → 文本的逻辑集中在 tui；本阶段在此之上加"渲染层"，不改 `AgentEvent`。
- 已有：`StatusLine`、`TaskList` 侧栏、子代理进度行（M8.5，`[sub-agent …] turn N`）、审批 diff（ext-hitl 经 `UserInteraction` 提供文本）。这些都要并入新视觉，不能两套风格。
- JLine 4：用 `Status` API 做底部固定行；`LineReader` 多行编辑与补全已可用；`Terminal` 宽度/能力探测用于降级。

## 9A 视觉系统与降级
- `Theme`：一套语义色（标题、强调、代码、工具、成功、警告、错误、弱化），深/浅底色各一组；`PAI_THEME=dark|light|none`，`none` 或 `NO_COLOR`/非 ANSI 终端 → 纯文本降级（现有 Plain 行为）。颜色只用 16/256 色 ANSI，不用真彩（Windows conhost 兼容）。
- 所有宽度计算用 JLine 的 `WCWidth`/`AttributedString.columnLength`，中文/emoji 正确对齐；终端 resize 后状态栏重绘。
- `Symbols` 保留 ASCII 回退。

## 9B Markdown 渲染（assistant 文本）
- 流式：按行增量渲染，支持标题（`#`）、列表（`-`/`1.`）、行内代码、**粗体**、代码块（围栏 ``` 语言标注 → 左侧竖线 + 背景/弱化色，不做语法高亮——留后续）、引用、分隔线、简单表格（按列对齐，超宽则不对齐直接输出）。
- 代码块与表格在流式中按"块结束"再渲染（块内缓冲），其他行实时。
- 流式期间显示 spinner + 已用秒数（在底栏，不插进正文）；结束后一行弱化的 `usage in=… out=… (N.Ns)` 替代现在的 `[usage …]`。

## 9C 工具调用卡片
- 每次工具调用一张卡：首行 `▸ edit src/Main.java` 风格（图标/工具名/关键参数摘要，现 `ToolCallSummaries` 升级），运行中 spinner，结束后 `✓ 0.3s` / `✗ exit 1 1.2s`。
- 输出默认**折叠**：显示前 N 行（默认 8）+ `… (+123 lines, /expand 3)`；`/expand <n>` 或 `/expand` 展开最近一张；`PAI_TUI_FOLD=0` 关闭折叠。错误输出默认多显示（20 行）。
- `edit`/`write` 显示**着色 diff**（新增绿、删除红、上下文弱化；行号），与审批时 hitl 给的 diff 文本共用同一渲染函数（hitl 产出纯文本 diff，tui 负责着色——不改 hitl）。
- `bash` 卡片显示命令（截断到一行，`/expand` 看全）与输出；并行调用的卡片按完成顺序各自收口，不交错。
- 子代理进度行并入卡片：`spawn_agent` 卡片内逐 turn 覆盖显示，结束显示 done/aborted/failed。

## 9D 底部区域
- JLine `Status` 固定两行：第一行输入提示（`>`），第二行状态栏：`[AUTO_READ] deepseek-chat · ctx 23% · 3/5 tasks · 2 sub-agents` + 流式时 spinner/秒数。
- 任务清单侧栏改为状态栏里的进度计数 + `/todo` 全文（宽终端 ≥120 列时保留右侧侧栏，否则只用计数）。
- 输出区域在上方滚动，终端原生滚动历史保留（不进入 alternate screen）。

## 9E 交互框
- 审批、`/model` 选择、`/tree`、记忆候选确认：统一的选择框组件（边框、标题、选项列表、方向键/数字/首字母、回车确认、Esc 取消），在输入行上方绘制，完成后擦除不留痕。审批框保留现有 `y/n/a/A` 键位并显示 diff 卡片。
- 实现经 `UserInteraction` 现有方法（`ask/confirm`）——若现有 SPI 只能返回字符串，选择框在 tui 内把方向键选择转成对应键字符返回；**不改 UserInteraction 接口**。

## 9F 兼容
- Plain renderer（`--quiet`、非 TTY、`PAI_THEME=none`）输出与现在一致（评测脚本、测试依赖它）；现有 tui 测试全部保留，新增渲染测试用 `AttributedString` 的纯文本投影断言内容，用 ANSI 序列断言颜色（抽样）。
- Windows Terminal、conhost（`cmd` 窗口）、Git Bash mintty 三种各做一次人工检查，报告附三张"文本录屏"（`script`/`tee` 的原始输出或 Plain 投影）+ 一句话差异说明。

## 不做
全屏/alternate screen、差量渲染框架、语法高亮、鼠标、补全弹窗、图片。

## 验收
- [ ] 一段含标题/列表/代码块/表格的回复在 Windows Terminal 下着色正确、中文对齐；`PAI_THEME=none` 下退回纯文本。
- [ ] 一次 `edit` 显示着色 diff；一次 100 行 bash 输出默认折叠，`/expand` 展开。
- [ ] 底栏两行固定，输出滚动不覆盖输入；resize 后重绘正确。
- [ ] 审批与 `/model` 选择用选择框完成；Esc 取消等价于 n。
- [ ] 子代理进度、任务计数并入新视觉；Plain 输出与 M8.5 一致（现有测试全绿）。
- [ ] tui ≤ 3000 行；core/cli diff 为空；报告 `docs/reports/M9-report.md` 附三种终端的录屏文本。
