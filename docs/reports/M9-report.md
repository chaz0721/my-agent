# M9 实施报告（TUI 升级）

- 分支：`impl/M9`（基于本地 `main` = `c230fe2`，即 origin/main `7b1a094` + 规格 docs commit）。未 push、未合入。
- CI：未跑（未 push）。电脑本地 `mvn -B -ntp verify` 绿，982 个测试（tui 60 个，M8.5 时 27 个，原有 27 个全部保留未改）。
- 实施 session：Claude Opus 5.5，2026-10-16。live 用 DeepSeek（`openai-compat/deepseek-chat`），默认 AUTO_READ，未开 yolo。脚本、录屏、fixture 都在 `D:\PaiCLI\tmp\m9live\`。
- 改动范围：只改 `pai-tui`。`git diff --stat main..impl/M9 -- pai-cli` 与 `-- pai-core` 均为空。

## 0. 做了什么（一段话）

`JLineRendererImpl` 在彩色主题下改走新的 `RichView`：assistant 文本按行渲染 Markdown（代码块、表格按块结束渲染），每个工具调用一张 `ToolCard`（运行中在 live 区带 spinner，结束后整张打印，输出默认折叠 8 行、失败 20 行，`/expand [n]` 展开），edit/write 显示带行号的着色 diff（和审批问题里 hitl 给的 unified diff 共用 `DiffView.render`），底部 `BottomBar` 一行状态栏（模式、模型、ctx%、任务计数、子代理数、spinner+秒数），所有固定答案的提问（审批、信任、记忆候选、verify 同意、规则保存）改为 `Picker` 选择框。无颜色（`PAI_THEME=none`、`NO_COLOR`、dumb/少于 8 色）时走原 M8.5 路径（提取为 `MonoSurface`），PTY 录屏逐字节与 M8.5 一致；`PlainRendererImpl` 未改一行，Windows 管道录屏与 M8.5 一致。

新增文件（非空行）：`Theme` 104、`Cols` 142、`Markdown` 260、`DiffView` 194、`ToolCard` 200、`RichView` 236、`BottomBar` 96、`Picker` 220、`MonoSurface` 156、`Chooser` 19。最大的 `JLineRendererImpl` 398（≤400）。

## 1. 验收清单

证据文件：Linux PTY 录屏用容器里 javac 编出的同一份代码跑真 `pai`（`scratchpad/ptyrun.py`），Windows 录屏用自写的 ConPTY 宿主（`m9live\ptyhost.ps1`，即 Windows Terminal 用的同一层伪控制台）跑电脑上 `mvn verify` 打出的 jar。投影用 `scratchpad/vt.py`（自写的 VT 模拟器：光标、滚动区、擦除、宽字符、DEC 线框字符集、resize 标记）把原始 ANSI 重放成"屏幕 + 回滚"纯文本。真实终端窗口另有截图（只截图，见 §7 第一条）。

- [x] **含标题/列表/代码块/表格的回复着色正确、中文对齐；`PAI_THEME=none` 退回纯文本**
  - fake provider 脚本（`!tool bash {"command":"cat sample.md"}`，echo 把 Markdown 原样回给渲染器），Linux xterm-256color 原始片段：
    ```
    echo: \r\n\x1b[96;1mM9 渲染样例\x1b[0m\r\n\r\nSome \x1b[1mbold\x1b[0m text, \x1b[33minline code\x1b[0m and \x1b[3mitalic\x1b[0m; 中文对齐测试。
    \x1b[1m名称\x1b[0m \x1b[90m │ \x1b[39;1mvalue\x1b[90;22m │ \x1b[39;1mnote\x1b[0m\r\n\x1b[90m──────┼───────┼─────\x1b[0m\r\n中文 \x1b[90m │ ...
    ```
    纯文本投影（Windows ConPTY，`WT_SESSION` 置位 = Windows Terminal 模式，`win-fake-wt`）：
    ```
    M9 渲染样例
    Some bold text, inline code and italic; 中文对齐测试。
    List
    • first item
    • second with code
      • nested 子项
    1. numbered one
    │ a quoted line
    ────────────────────────────────────────────────────────────────────────────────
    │ java
    │ class Main {
    │     int x = 1; // 注释
    │ }
    名称  │ value │ note
    ──────┼───────┼─────
    中文  │ 12    │ ok
    ascii │ 3456  │ bold
    ```
    "名称/中文"占 4 列、"ascii"占 5 列，竖线对齐（`MarkdownTest.tables_align_by_columns_with_cjk_counted_twice` 断言每行竖线前的列宽相等）。真实 Windows Terminal 截图上同样对齐、颜色正确（标题亮青粗体、行内代码/代码块黄、弱化灰）。
  - 两套主题的 SGR 统计（Linux 录屏全部 escape）：dark 只有 `0 1 3 32 33 36 36;1 39;1 90 90;22 91 96;1`，light 只有 `0 1 3 31 32 34 34;1 35 39;1 90 90;22`；两份录屏里 `38;5`、`38;2` 出现 0 次（只用 16 色，`ThemeTest.colours_are_basic_ansi_only`）。
  - `PAI_THEME=none`：同一按键脚本（`steps-line.json`，审批用 `y⏎`）分别跑 M8.5 代码与 M9 代码，PTY 原始字节去掉 session id 和耗时后 **diff 为空**；`NO_COLOR=1` 也与 M8.5 字节一致。

- [x] **一次 edit 显示着色 diff；100 行 bash 默认折叠，`/expand` 展开**
  - DeepSeek 会话的原始片段（Windows ConPTY）：
    ```
    \x1b[32m\x1b[7;1H✓ \x1b[36m\x1b[1medit\x1b[m Main.java \x1b[90m· 0.0s\x1b[m\r\n  \x1b[90m2 \x1b[91m-     int x = 1;\x1b[m\r\n  \x1b[90m2 \x1b[32m+     int x = 2;\x1b[m
    ```
  - 折叠与展开（fake，`win-fake-wt` 投影）：
    ```
    ✓ $ seq 1 100 · exit 0 · 0.9s
      1
      ...
      8
      … (+92 lines, /expand 2)
    > /expand
    ✓ $ seq 1 100 · exit 0 · 0.6s
      $ seq 1 100
      1
      ...（全部 100 行）
    ```
  - `PAI_TUI_FOLD=0` 关闭折叠（`RichRendererTest.fold_zero_shows_everything`），失败输出至少 20 行（`ToolCardTest.a_non_zero_exit_is_a_failure_and_shows_more_lines`；bash 非零退出码在卡片上记为 ✗，工具本身不算错误）。

- [x] **底栏固定，输出滚动不覆盖输入；resize 后重绘正确**
  - 底栏始终是 Status 区最后一行，输入行在它上方（JLine 原生做法，见偏离 4）。录屏终屏：`[AUTO_READ] echo · ctx 1%`；真实 WT 截图底栏：`[AUTO_READ] deepseek-chat · ctx 23% · 3/5 tasks · 1 sub-agent · ⠼ 14.5s`。
  - resize：Linux PTY 100x30 → 40x20 → 100x30（`steps-b.json`），40 列下卡片标题按列宽截断、事实（exit/秒数）保住：`✓ $ seq 1 12; echo 中... · exit 0 · 0.0s`；`RichRendererTest.after_a_resize_every_live_line_fits` 断言 resize 后每行 ≤ 30 列（含 50 个中文的命令）。Windows ConPTY 的 resize 投影在回到 100 列后有一行残影，原因未定（可能是 ConPTY 自身的重排，我的模拟器不模拟它），记为未验证。
  - 这一条在真实 WT 上抓到一个 bug 并修了（`25a3468`）：live 区变高时 JLine `Status` 先滚屏、再按下标 diff，只差 spinner 一格的卡片行被"补丁"到一个空行上，屏上只剩 spinner。修法：行数变化时先画一遍空行再画实际内容。修前修后的 WT 截图、Linux 复现、以及修前失败修后通过的回归测试 `a_running_card_is_redrawn_whole_after_the_live_area_grows` 都有。

- [x] **审批与 `/model` 选择用选择框完成；Esc 取消等价于 n** —— 审批完成；`/model` **未**做成选择框（偏离 2）
  - fake 脚本：`seq 1 100` 审批按 ↓ ↑ ⏎ → `allow? ❯ y (yes)`；edit 审批按 ⏎；`exit 3` 审批按 Esc → `allow? ❯ cancelled n (no)`，hitl 回灌 `tool call blocked: user denied`（Linux、Windows WT 模式、Windows conhost 模式三份录屏一致；conhost 模式字形为 `allow? > y (yes)`）。
  - 真实 WT 截图里的框（选择框画在 live 区，结束后被一行弱化记录替换）：
    ```
    ╭─ allow? ───────────────────────────────────────────╮
    │ ❯ y  yes                                           │
    │   n  no                                            │
    │   a  always for bash in this session               │
    │   A  always, saved as a rule (shown before saving) │
    ╰─ up/down, Enter, Esc ──────────────────────────────╯
    ```
  - 键位（审查修复后，§10）：↑↓、数字 1..n、选项字母（`a`/`A` 区分大小写）只移动高亮，⏎ 确认高亮项，无高亮时 ⏎ 无效；Esc/Ctrl+C/Ctrl+D 取消（有 `n` 选项时返回 `n`，否则返回空=无答案）。上面 fake 脚本与 DeepSeek 录屏是审查前录的，那时 `y` 直接作答、⏎ 默认 y。测试 `approvals_need_a_highlighted_choice_and_enter_and_type_ahead_never_answers` 与 `PickerTest`。

- [x] **子代理进度、任务计数并入新视觉；Plain 输出与 M8.5 一致（现有测试全绿）**
  - 子代理：运行中卡片 `⠼ spawn_agent explore: find callers of effectOf` 下一行 `turn 2: grep effectOf; read Tool.java`，结束 `✓ spawn_agent explore: … · done (1 turns) · 0.0s`；状态栏 `1 sub-agent`。任务：`echo · ctx 1% · 1/3 tasks`；≥120 列时任务清单留在 live 区。
  - Plain：Windows 上同一脚本经管道喂给 M8.5 jar 和 M9 jar（`plain.ps1`，cmd 重定向保留原始字节），两份都是 1702 字节，`fc /b` 的差异只在 session id 段（偏移 0x71 起），去掉 session id 和耗时后完全相同（`plaincmp2.ps1` 输出 `PLAIN IDENTICAL`）。
  - 原有 27 个 tui 测试一个没改，全绿。

- [x] **tui ≤ 3000；core/cli diff 为空；三种终端录屏** —— tui 2902/3000；cli、core diff 为空；三种终端见下。

### 三种终端的"文本录屏"

我对终端窗口只有截图和点击权限，不能向它们发送按键（授权明确禁止用脚本绕过），所以交互部分用 ConPTY 宿主录屏，真实窗口只看渲染：真实窗口里跑一个无需输入的演示程序 `WinDemo.java`（通过公开 API `JLineRendererImpl.system()` 回放一段事件：流式 Markdown、edit、100 行 bash、失败的 mvn、运行中的子代理，最后停在一个审批框上），以及空闲的真 `pai`。

1. **Windows Terminal**（真实窗口截图 + ConPTY 录屏 `win-fake-wt`、`win-ds`）：Unicode 字形、16 色、中文表格对齐、diff 红绿、折叠提示、选择框圆角、底栏 spinner 都正确。截图里状态栏原文：`[AUTO_READ] deepseek-chat · ctx 23% · 3/5 tasks · 1 sub-agent · ⠼ 14.5s`。差异：本机代码页是 GBK，M9 初版在这里退回 ASCII 字形，后来按 `WT_SESSION` 判断改成 Unicode（`c7b56b4`，见 §7）。
2. **conhost（cmd 窗口）**（真实窗口截图 + ConPTY 录屏 `win-fake-con`）：颜色正确，中文对齐，字形全部 ASCII 回退：`ok $ seq 1 100 | exit 0 | 1.2s`、`... (+92 lines, /expand 2)`、`+- allow? ---+`、spinner `|/-\`。差异：粗体在 conhost 默认字体下看不出来；"一句话"：conhost 可用但朴素。
3. **Git Bash（mintty）**（真实窗口截图，空闲）：`pai` 在 mintty 下也开了 JLine 富渲染（mintty 走 ConPTY），顶部 `> ` 输入行、底部灰色状态栏 `echo | ctx 1%`，字形为 ASCII 回退（无 `WT_SESSION`，GBK）。差异：启动横幅 `model=… session=…` 在 mintty 窗口里看不到（ConPTY 初始化清屏所致，推断）。我无法在 mintty 里输入，空闲以外的交互**未验证**。

### DeepSeek 真实会话（Windows ConPTY，Windows Terminal 模式，`win-ds` 投影节选）

提示：先用 edit 把 `int x = 1;` 改成 `int x = 2;`，再用 bash 跑 `seq 1 100`，最后用 Markdown 总结（标题、列表、java 代码块、两列表格）。脚本按 ⏎ 发送，第 31s 和 51s 各按一次 `y`，再 `/expand`、`/quit`。录屏里 key 形态字符串 0 处。

```
I'll start by reading Main.java to locate the exact text.
✓ read Main.java · 7 lines · 0.0s
The file has int x = 1;. Applying the edit and running the command.
tool edit (write_fs)
--- D:\PaiCLI\tmp\m9live\fx-ds\Main.java
+++ D:\PaiCLI\tmp\m9live\fx-ds\Main.java
@@ -1,5 +1,5 @@
1   class Main {
2 -     int x = 1;
2 +     int x = 2;
4       public static void main(String[] args) {
5           System.out.println(new Main().x);
allow? ❯ y (yes)
tool bash (execute)
$ seq 1 100
allow? ❯ y (yes)
✓ edit Main.java · 0.0s
  2 -     int x = 1;
  2 +     int x = 2;
✓ $ seq 1 100 · exit 0 · 1.4s
  1 … 8
  … (+92 lines, /expand 3)
Task Summary

• Edited Main.java:2, changing the field initializer from 1 to 2.
• Ran seq 1 100, which printed the integers 1 through 100 and exited with code 0.
• The program now prints 2 instead of 1.

│ java
│     int x = 2;

Step           │ Result
───────────────┼───────────────────────────────
Edit Main.java │ int x = 1; → int x = 2;
Run seq 1 100  │ 100 lines (1–100), exit code 0
usage in=1452 out=309 cacheRead=5376 (47.8s)
> /expand
✓ $ seq 1 100 · exit 0 · 1.4s
  $ seq 1 100
  1 … 100
```
edit 和 bash 是同一 turn 的两个调用：两个审批框依次弹出，两张卡按完成顺序各自收口。

## 2. `build/check-budgets.sh`

```
pai-model LOC=2683 (max 4000)
pai-core LOC=5249 (max 5500)
pai-cli LOC=1998 (max 2000)
pai-tui LOC=2902 (max 3000)
pai-ext-hitl LOC=1764 (max 2500)
pai-ext-mcp LOC=2166 (max 2500)
pai-ext-memory LOC=1187 (max 2500)
pai-ext-subagent LOC=1169 (max 2500)
pai-ext-tasks LOC=384 (max 2500)
pai-ext-verify LOC=789 (max 2500)
budgets OK
```

## 3. `git diff --stat main..impl/M9`（代码部分，报告 commit 之前）

```
 pai-tui/src/main/java/dev/pai/tui/BottomBar.java   | 104 +++++++
 pai-tui/src/main/java/dev/pai/tui/Chooser.java     |  22 ++
 pai-tui/src/main/java/dev/pai/tui/Cols.java        | 155 ++++++++++
 pai-tui/src/main/java/dev/pai/tui/DiffView.java    | 207 +++++++++++++
 pai-tui/src/main/java/dev/pai/tui/EventView.java   |  45 ++-
 .../main/java/dev/pai/tui/JLineRendererImpl.java   | 339 ++++++++++++++-------
 pai-tui/src/main/java/dev/pai/tui/Markdown.java    | 278 +++++++++++++++++
 pai-tui/src/main/java/dev/pai/tui/MonoSurface.java | 180 +++++++++++
 pai-tui/src/main/java/dev/pai/tui/Picker.java      | 240 +++++++++++++++
 .../src/main/java/dev/pai/tui/RendererFactory.java |  21 +-
 pai-tui/src/main/java/dev/pai/tui/RichView.java    | 261 ++++++++++++++++
 pai-tui/src/main/java/dev/pai/tui/Symbols.java     |  49 ++-
 .../java/dev/pai/tui/TerminalInteractionImpl.java  |   9 +-
 pai-tui/src/main/java/dev/pai/tui/Theme.java       | 114 +++++++
 .../main/java/dev/pai/tui/ToolCallSummaries.java   |   2 +-
 pai-tui/src/main/java/dev/pai/tui/ToolCard.java    | 217 +++++++++++++
 .../src/test/java/dev/pai/tui/MarkdownTest.java    |  97 ++++++
 .../test/java/dev/pai/tui/RichRendererTest.java    | 269 ++++++++++++++++
 pai-tui/src/test/java/dev/pai/tui/ThemeTest.java   |  90 ++++++
 .../src/test/java/dev/pai/tui/ToolCardTest.java    | 161 ++++++++++
 20 files changed, 2733 insertions(+), 127 deletions(-)
```
既有文件的改动：`EventView` 把子代理行解析抽成静态 `turnLine`/`ending`/`total` 供 `RichView` 复用（输出不变，`SubagentProgressTest` 原样通过）；`ToolCallSummaries.text` 由 private 改包内可见；`Symbols` 加 `unicode` 分量和线框字形（`start/ok/fail/dot` 的判定字符串不变，Plain 输出不变）；`TerminalInteractionImpl.ask` 前加 `Chooser` 分支（Plain 渲染器不实现它，行为不变）；`RendererFactory` 加 `PAI_TUI_FOLD`；`JLineRendererImpl` 拆出 `MonoSurface`（M8.5 的 live 面，逻辑原样搬移，截断改用列宽 `Cols.cut`，对 ASCII 结果相同）。

## 4. 签名异议

无。`Renderer`、`UserInteraction` 接口未改。新增公开成员登记：`Theme`（record，含 `DARK/LIGHT/NONE`、`THEME_ENV`、`NO_COLOR_ENV`）、`JLineRendererImpl(Terminal, Theme, int)`、`JLineRendererImpl(Terminal, Theme, int, boolean)`、`RendererFactory.FOLD_ENV`。原 `JLineRendererImpl(Terminal)` 保留且语义不变（无颜色）。

## 5. SPI 漏洞

1. **审批模式对渲染器不可见。** `StatusLine` 不含模式，tui 不能依赖 hitl，只能从 hitl 打印的 `approvals: mode …` / `plan mode on` 文本里嗅探（`BottomBar.modeIn`）。启动后到第一次打印之前状态栏不显示模式（默认 AUTO_READ 也不显示，避免猜错 `--approval ask`）。修法：`StatusLine` 加 mode 分量，由 cli 从 hitl 设置里填（要改 cli）。
2. **tui 不能注册斜杠命令。** `/expand` 由 `JLineRendererImpl.readLine` 在行交给 REPL 前截获处理，所以 `/help` 里没有它。
3. **`/model`、`/tree` 是 cli 命令，输出纯文本，不经过 `UserInteraction`。** 选择框没法接管它们（偏离 2）。
4. `UserInteraction` 只有 `ask(prompt, choices)`：选项含义靠解析问题末行 `k = 含义, …`（hitl、记忆候选是这种格式），解析不到时显示 `y→yes / n→no / 原字符`，问题全文作为正文打印在框上方。

## 6. 对规格的偏离与理由

1. **`PAI_THEME=none` / `NO_COLOR` / 非 ANSI 终端退回的是 JLine 渲染器的 M8.5 行为，而不是 `PlainRendererImpl`。** cli 的 `Main.interaction` 只在渲染器是 `JLineRendererImpl` 时才提供交互（审批），换成 Plain 会让 `PAI_THEME=none` 的用户所有需审批调用被拒；不改 cli 只能这样。效果上是"无颜色、输出与 M8.5 逐字节一致"（§1 PTY diff 为空），`--quiet`/非 TTY 仍是 Plain，完全没动。
2. **`/model`、`/tree` 没有做成选择框。** 原因见 §5.3；要做需要 cli 把候选列表经 `UserInteraction.ask` 交给 tui（cli 封顶，按规格先停下汇报，这里记为未完成项）。已做成选择框的：审批、规则保存确认、项目信任、记忆候选（总问与逐条）、记忆清空确认、verify 首次运行同意——即全部 `ask/confirm` 调用点。
3. **`/expand` 不在 `/help` 里**，由渲染器截获（§5.2）。没有另做 Ctrl+O。注意截获范围是整行匹配 `/expand` 或 `/expand <任意>`：`/expand foo` 也被截获，显示 `usage: /expand [card number]`，不会交给 REPL。
12. **无颜色路径继承 M8.5 的正文原样输出**：`PAI_THEME=none`/`NO_COLOR` 下 assistant 正文和 `println` 仍按 M8.5 原样写出（不做 `clean`），这是为了与 M8.5 字节一致；彩色路径的正文经 `Cols.clean`。审查记录为既有约定，后续统一在渲染器入口处理（审查 P2-2 说明）。
4. **"底部两行"**：输入行就是 JLine `LineReader` 的编辑行，位于 Status 区正上方；Status 区最后一行是状态栏。运行中 live 区在状态栏之上按需增高（运行中的卡片、推理、未完成的文本行、选择框），超过半屏时最旧的行让位。
5. **宽终端侧栏**：≥120 列时任务清单留在 live 区（状态栏上方），不是右侧一列；窄终端只显示计数，`/todo` 看全文。
6. **Markdown 以行为单位**：未写完的行不进正文，以弱化色显示在 live 区，换行后才打印；代码块和表格在 live 区显示 `… code block, N lines` / `… table, N rows`。
7. **edit 卡片的 diff 来自调用参数**（`oldText`→`newText` 的行级 LCS），行号从 edit 工具结果里带行号的片段反查；`write` 卡片把内容显示为全部新增（没有改前内容可比）。审批里的 diff 仍是 hitl 的文本，tui 只负责解析着色，两者共用 `DiffView.render`。
8. **选择框没有默认项**（审查 P0 后改，见 §10）：打开时不高亮任何选项，⏎ 在无高亮时不作答；↑↓、数字、选项字母只移动高亮，⏎ 才确认，`y/n` 也不例外；Esc/Ctrl+C 在有 `n` 选项时等于 n。规则保存确认（confirm）同样无默认。框出现前和出现后 250ms 内输入的键全部丢弃。
9. **Esc = n 只在 `n` 是选项时成立**，否则 Esc 返回空（无答案）。
10. **commit 粒度**：9D 与 9E 在一个 commit（渲染器接线与选择框共用 `JLineRendererImpl`），之后是三个修复 commit（录屏和真实 WT 截图里发现的问题）。9F 没有单独 commit：兼容核对的结果都在报告里。
11. **Windows Terminal 下强制 Unicode 字形**（`WT_SESSION`），规格没写，见 §7 第 5 条。

## 7. 坑（给后来者）

1. **终端窗口只有 click 级权限。** 计算机使用授权把 Windows Terminal、cmd、mintty 都限制为"只能看和左键点"，并明确禁止用脚本或其他手段向它们发按键。我一开始写的 `conio.ps1`（`WriteConsoleInputW` 注入按键）因此**没有使用**，审查后已从 `m9live` 删除。交互测试改用自写的 ConPTY 宿主 `ptyhost.ps1`（`CreatePseudoConsole` + 管道，和 Linux 的 `ptyrun.py` 对等，不涉及任何终端 GUI），真实窗口只截图。
2. **ConPTY 宿主的子进程会继承宿主的重定向 std 句柄。** desktop-commander 启动的 PowerShell 的 stdio 是管道，子进程直接写到了管道里而不是伪控制台。`CreateProcess` 前把本进程三个 std 句柄临时设成 NULL 再恢复即可（`ptyhost.ps1`）。
3. **JLine 4 `Status` 增高时的 diff bug**（`JLineRendererImpl.redraw`）：增高时它先滚屏再按下标与旧行比较，只差一格的行会被局部补丁到空行上。修法见 §1；回归测试检查最后一次设置滚动区之后卡片文本被重新写出。`MonoSurface`（M8.5 路径）没有加这个处理，保持字节一致；M8.5 的 live 区行内容变化大，没有观察到同样的问题，但理论上存在。
4. **`AttributedString.toAnsi()` 在没有终端（或终端不支持备用字符集）时把 `│─┼` 等线框字符换成 ASCII `|-+`**；传终端进去又会切到 DEC 线框字符集（`ESC(0 x`）。打印到正文时用 `Cols.ansi`：先把线框字符换成私用区码位、编码、再换回来。Status 区仍由 JLine 自己画，xterm/windows-vtp 走 DEC 字符集，显示正常。
5. **Windows 的输出编码是代码页（本机 GBK），`Symbols.forCharset` 会因此退回 ASCII**，但 JLine 的 JNI 终端用 `WriteConsoleW` 写 UTF-16，编码并不是限制，限制的是字体。现在：有 `WT_SESSION`（Windows Terminal，字体完整）且是彩色路径时用 Unicode；conhost 保持 ASCII（默认字体缺 `✓▸❯⠋`）；无颜色路径不变。
6. **`device_commit_files` 复用同一 `stagedPath` 会发出旧内容**（M8.5 已记，这次又踩一次：第一次修复 commit 时电脑上还是旧文件，靠 tui LOC 没变察觉）。之后每次用新目录 `outputs/syncN/`，提交后核 SHA-256。
7. **`CON` 是 Windows 保留设备名**，`con.ps1` 写不进去（报 "path changed between validation and write"）。
8. **`device_stage_files` 只支持 7 层以内**，`pai-tui/src/main/java/dev/pai/tui/*.java` 是 8 层。spotless 格式化后的文件用 `tar -a -c -f x.zip pai-tui/src` 打包拉回。
9. **JLine `LineReader` 会吃掉反斜杠**：在 pai 输入行里敲 `\n` 得到 `n`（M8.5 起就如此，`!tool edit {..."newText":"a\nb"}` 要写成 `\\n`）。
10. **虚拟终端测试里 Ctrl+C 有竞态**：在进入 raw 模式前写入的 `^C` 会被 JLine 的行规程当成信号。测试里用 Ctrl+D 代替（`RichRendererTest`）。
11. PMD 6（p3c）把 record 的分量当成变量名报 `LowerCamelCaseVariableNamingRule`（`BottomBar.Extras`，与 core 的 `SystemPromptBuilder` 同类），报告型，不处理。p3c 不许 `Executors.newSingle…`，spinner 线程改为直接 `new ScheduledThreadPoolExecutor`。

## 8. 建议（没有做）

1. `StatusLine` 加审批模式（cli 一行），去掉文本嗅探。
2. `/model`、`/tree` 选择框：cli 在无参数时用 `UserInteraction.ask` 列候选（需先拆 cli 模块）。
3. 表格单元格补空格时继承了上一段样式（行内代码后面的补白也是黄色，背景无色所以看不出来）；以后若加背景色要先修。
4. 代码块语法高亮（规格留待后续）；diff 内的词级高亮。
5. conhost 可选 Unicode：给 `PAI_TUI_GLYPHS=unicode|ascii` 一个手动开关。
6. （已在审查修复中改为无默认，见 §10。）

## 9. 给下一阶段的输入

1. 自用时留意：Windows Terminal 下 spinner 每 120ms 重绘 Status 区，CPU/闪烁是否可接受；conhost 下是否需要 Unicode 开关。
2. 选择框"无默认 + 250ms 防抖 + 必须 ⏎"是否太繁琐（审批多时每次至少两键）。
3. 宽终端（≥120 列）任务清单占 live 区高度，与多个运行中卡片叠加时会顶掉最旧的行。
4. `/expand` 只保留最近 50 张卡；卡片编号会话内递增，`/clear` 后不重置。

## 未验证项

- conhost、mintty 里的**交互**（按键、选择框作答、`/expand`）：只有渲染截图和空闲截图；交互部分由 ConPTY 录屏覆盖（同一 conhost 内核），但真实窗口里没按过键。
- 真实 Windows Terminal 窗口的 resize（拖拽被禁止）；ConPTY 录屏 resize 后的那行残影原因未定。
- light 主题在浅色背景上的观感（只看了 SGR，没有浅色终端截图）。
- macOS Terminal / iTerm。
- mintty 下启动横幅消失的原因（推断为 ConPTY 初始化清屏）。

## 附录：复现

- Linux：`scratchpad/buildall.sh`（javac 编全部模块）→ `runfake.sh OUT 100 30 steps-fake.json [PAI_THEME=…]` → `python3 vt.py OUT 100 30`。
- Windows：`m9live\plain.ps1`（Plain 对比）、`runpty.ps1 -Name none-m9 -Steps steps-line.json -EnvSet PAI_THEME=none`（无颜色路径；`steps-line.json` 已复制到 `m9live`）、`runpty.ps1 -Name fake-wt -Steps steps-fake.json -EnvSet WT_SESSION=m9-check`、`runds.ps1`（DeepSeek）、`demo.ps1 -Kind wt|conhost|mintty`（真实窗口演示，只截图）。
- 录屏原件：`m9live\pty-*.ansi`、`plain-m85.txt`、`plain-m9.txt`。

## 10. 审查修复

审查结论是"不能按现状合入"：1 个 P0、1 个 P1、10 项 P2。下面全部修完，都在 `impl/M9` 上；cli、core 仍然零改动。修完后电脑上 verify 绿，tui 预算 2993/3000，tui 测试 74 个。

**P0 审批选择框会被预输入、组合键、粘贴直接作答（`A` 会写入永久规则）。** 修在 `Picker.run`：
1. 框画出、进入 raw 模式后先**排空输入缓冲**（循环 `read(1)` 直到超时，全部丢弃），之后 `GUARD_MILLIS` = 250ms 内到达的键也全部丢弃。
2. **完整解析转义序列**：`ESC [` / `ESC O` 之后一直读到终结字节 0x40–0x7E 为止，作为一个整体。只有不带参数的 ↑↓（`[A [B OA OB`）有含义，其余序列整段忽略：Ctrl/Alt/Shift+箭头、F 键、Home、`ESC [[A`、Alt+字母。括号粘贴 `ESC[200~ … ESC[201~` 整段忽略。单独的 ESC 等待 200ms（原来是 80ms）。
3. **没有默认高亮**：打开时什么都没选，⏎ 在没有选中项时不作答。↑↓、数字、选项字母只移动高亮，⏎ 才确认，`y/n` 也不例外。Esc 仍等于 n。规则保存确认（confirm）同样无默认。另外加了一条：输入一个不对应任何选项的字符会**清除**高亮，这样在框可见时敲 `add tests⏎` 或 `yes⏎` 都不会作答（否则首字母 `a`/`y` 会被选中再被 ⏎ 确认）。
4. 返回前再排空一次输入缓冲，残留字节不会流进下一个提示或 REPL 输入行。
5. 回归测试：`PickerTest` 用脚本化的按键时间线和假时钟（每个键都带到达时间），逐条覆盖：
   - `type_ahead_does_not_answer_and_a_later_choice_does`：预输入 `Add tests⏎`、`fix the bug⏎`、单个 ⏎、单个 `A`；
   - `keys_right_after_the_box_appeared_are_ignored`：100ms、249ms 时到达的键；
   - `enter_answers_only_a_highlighted_choice_and_keys_only_move_the_highlight`：无高亮时的 ⏎；`y`、`3` 只移动高亮；`add tests⏎`、`yes⏎` 不作答；↓↓⏎ → n；
   - `modified_arrows_function_keys_and_alt_keys_are_ignored_whole`：Ctrl/Alt/Shift+箭头、F12、F1、Home、`ESC[1~`、`ESC[[A`、Alt+A、Alt+y，每段都被完整读走，不重绘、不残留；
   - `a_bracketed_paste_is_ignored_whole`：粘贴内容里含 `y⏎A⏎↑⏎`；
   - `an_arrow_split_by_a_slow_link_moves_the_highlight_and_answers_nothing`：`ESC` 与 `[A` 之间隔 120ms；
   - `nothing_typed_during_the_box_is_left_for_the_next_reader`；
   - `the_box_opens_with_nothing_highlighted`。

   渲染器层的 `RichRendererTest.approvals_need_a_highlighted_choice_and_enter_and_type_ahead_never_answers` 在虚拟终端上先预输入 `Add tests⏎`，再依次问 6 次，最后断言下一次 `readLine` 只得到 `hello`。
   - **修复前失败、修复后通过的证据**：把新测试编译到审查前的代码（`25a3468`）上运行：`PickerTest` 9 个里 8 个失败，`approvals_need_…` 和 `the_box_lists_…` 也失败。只有 `a_lone_escape_cancels_after_the_wait` 在新旧代码上都通过，这符合预期。修复后 74/74 通过。
   - **端到端证据**（Windows ConPTY，同一按键脚本 `steps-p0.json`：发出 `!tool bash seq 1 3` 后立即预输入 `Add tests⏎`，3s 后依次按 Ctrl+↑、F12、⏎，再输入 `fix the bug⏎`，最后按 Esc）：
     - 修复前（`pai-pre-review.jar`）：`allow? ❯ A (always, saved as a rule …)` → `choose ❯ y (yes)` → `! approvals: saved "allow bash seq *"`，`home-p0-before\ext\hitl\permissions.json` 被写入 `{"tool":"bash","pattern":"seq *"}`；之后 `> [1;5A` 和 `> fix the bug` 还作为提示词进入了 REPL。
     - 修复后：`allow? ❯ cancelled n (no)` → `tool call blocked: user denied`；没有生成 `permissions.json`；REPL 里没有残留输入。第二次审批按 ↓⏎ → `allow? ❯ y (yes)`。

**P1 推理文本的未完成行进入 live 区之前没有经过 `Cols.clean`。** `RichView.live` 和 `MonoSurface.lines` 现在都先 `clean` 再 `cut`；`MonoSurface` 里已完成的推理行也一样处理（正常文本的字节不变；tab 变成 4 个空格只影响 live 区，不影响正文）。测试 `reasoning_cannot_write_escape_sequences_to_the_coloured_live_area` 和 `…_uncoloured_live_area` 用 OSC0、`ESC[2J`、C1 CSI、RLO、OSC8、OSC52 构造推理文本，断言 live 区里没有任何原始 ESC/C1/RLO/BEL。这两个测试在修复前的代码上都失败。

**P2：**
1. `BottomBar.modeIn` 改为锚定行首：`^(plan mode off: )?approvals: mode `（`plan mode on` 原本就是行首匹配）。像 `echo: approvals: mode yolo` 这种伪造的行不再改变状态栏。
2. 展开后的 bash 命令行改用 `visible()`，tab 换成空格，与标题一致；控制字符显示成 `\x1b`，不再静默删掉。
3. `visible()` 改为按 code point 处理，新增 U+200E/200F/061C、U+2028/2029、U+FFF9–FFFB，以及标签字符 U+E0000–E007F（显示为 `\ue0041` 这种形式）。emoji 等代理对照常显示。
4. `Cols.cut` 把 `\t` 换成空格（`WCWidth` 对 tab 返回 -1，原来会把列宽算少）。
5. `PAI_TUI_FOLD` 为负数时视为无效，用默认值 8。
6. 状态栏里的 model 经过 `visible()`。
7. `DiffView.edit` 先去掉公共前缀和后缀，只对中间部分做 LCS（前后各 2000 行相同时也不会走"全删全加"的退化路径，有测试）。`ToolCard.firstLine` 不再用 `contains(strip)` 反查：找不到唯一匹配的行就不显示行号。
8. `Cols.ansi`：输入里原本就有的 U+E000–E00A 先替换成 U+FFFD，再做线框字符的占位替换，避免误变成 `│─`。
9. **已删除 `D:\PaiCLI\tmp\m9live\conio.ps1`**（未使用的按键注入脚本）。
10. 报告：§6.8 改写为无默认；§6.3 补充 `/expand foo` 也会被截获、显示 usage；§6.12 说明无色路径继承 M8.5 的正文原样输出；`steps-line.json` 已复制到 `m9live`，附录给出了 Windows 上的复现命令。

P2 的测试：`ThemeTest.review_p2_hardening`（1、3、4、5、6、8）、`ToolCardTest.the_expanded_command_shows_control_characters_instead_of_dropping_them`（2）、`ToolCardTest.an_edit_diff_keeps_numbers_through_a_long_common_head_and_tail`（7）、`the_first_line_comes_from_the_numbered_snippet` 新增的不唯一和不匹配用例（7）。

审查列为"记录不修"的项（由主控写）：rich/mono 的 `println` 与 mono 正文原样输出、setext 标题和 `~~~` 围栏、`\r` 的显示策略、慢链路 ESC 等待时间、无色路径截断改按列宽。
