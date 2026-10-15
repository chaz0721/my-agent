# M7 性能基线（bench-M7）

- 日期：2026-10-15。机器：用户的 Windows 11 电脑，Java 21.0.10（Oracle），Maven 3.9.14。provider 是 `fake`（`FakeProviderImpl`，脚本回复），没有网络和模型时间。
- 命令：`powershell -NoProfile -ExecutionPolicy Bypass -File build\bench.ps1 -Runs 5`（Linux/macOS 用 `bash build/bench.sh 5`）。脚本先跑 `mvn -q package -DskipTests`，再用 `-Dpai.bench=1` 跑 `pai-cli` 的 `BenchTest`（平时 `mvn verify` 里这个测试是 skipped），最后计时两次端到端启动。
- 只记录数字，不设门槛。进程内的数字是中位数（先 warmup，再计时，次数写在下表的"测法"列）；端到端启动取 5 次的中位数。原始输出见 `D:\PaiCLI\tmp\m7live\bench.log`。

| # | 项目 | 结果 | 测法 |
|---|---|---|---|
| 1 | 冷启动到提示符 | **1031 ms** | `"/quit" \| java --enable-native-access=ALL-UNNAMED -jar pai-cli.jar --provider fake --cwd <空目录>`，`PAI_HOME` 是空目录，用 `Measure-Command` 计时 |
| 2 | 1000 条 entry 的 session `--resume` 加载 | **858 ms** 端到端；store 部分 **17.5 ms** | 端到端：同 #1，加 `--resume`，session 由 `BenchTest` 生成（500 对 user/assistant，各 200/400 字符）。store 部分：每次新建 `JsonlSessionStoreImpl`，执行 `leafId` + `buildContext`；warmup 5 次，计时 21 次 |
| 3 | 一次 `read` 工具的 loop 开销（不含模型） | **1.63 ms** | `AgentLoop.run`：脚本回复 1 个 `read` 调用（2 KB 文件）+ 1 条结束回复；装了 classpath 上的全部扩展（hitl 为 auto-read，无 ui）；warmup 200 次，计时 501 次 |
| 4 | 微压缩 200 条消息耗时 | **0.127 ms**；估算 39195 → **4020** token | `MicroCompactor.apply`，默认策略。200 条消息 = 50 轮 ×（调用 + 3 KB 结果 + 调用 + 100 B 结果）；warmup 500 次，计时 2001 次。token 是 chars/4 估算 |
| 5 | 全量摘要前后的 token 估算 | **40382 → 2085** token | 160 条消息（40 轮，每轮读一个 4 KB 结果）。`compact(force)`，摘要固定为六节，剩 9 条消息 |
| 附 | 侧栏查找 `SessionEntriesImpl.latest`（每次 append 之后） | M6 全量重读 **4.35 ms** → M7 增量解析 **1.20 ms** | session 约 1000 条 entry（各 300 字符）。每次先 append 一条 message（leaf 移动，分支缓存失效），再查找；各 warmup 20 次，计时 101 次。"全量"按 M6 的做法 `SessionTree.of(store.readAll).path(leaf)` 重做一遍 |

## 解读
- #1 和 #2 主要是 JVM 启动和类加载的时间。#2 比 #1 还低，因为两组都只跑了 5 次、属于噪声范围；store 本身加载 1000 条只要 17 ms，`--resume` 没有明显的额外成本。
- #3 说明 loop 本身（事件分发、hooks、schema 校验、`read` 执行）每次工具调用大约 1.6 ms，跟模型延迟（秒级）比可以忽略。
- #4 说明微压缩足够便宜，可以每次请求都重算投影；投影后估算降到原来的约 10%。
- 附表是做增量解析的理由：M6 时每次 MessageEnd 都会移动 leaf，缓存基本命中不了，每次查找都要把整个文件重新读一遍、重新解析。现在只解析新追加的完整行，查找时间里剩下的主要是 append 本身和对 1000 条 entry 建 `SessionTree`。
