# 实施/审查窗口的执行环境

两个地方，各有分工：

| | 云容器（你的 Read/Write/Edit/Bash 工具所在） | 用户的 Windows 电脑（Desktop Commander 工具 `mcp__remote-devices__plugin_…desktop-commander__*`） |
|---|---|---|
| 用途 | 写代码、读 pi 参考源码、读旧仓库快照 | **构建、测试、git、live 测试** |
| 仓库路径 | `/home/claude/pai-next`（工作副本） | `D:\PaiCLI\pai-next`（**权威副本**，git 仓库在这里） |
| Maven Central | **不可达**（组织策略 403，不要重试） | 可达 |
| Java / Maven | 21 / 3.9（只能编译不能拉依赖） | 21.0.10 / 3.9.14 |
| API key | 无 | 用户环境变量 `PAI_ANTHROPIC_API_KEY`、`PAI_OPENAI_COMPAT_API_KEY`、`PAI_OPENAI_COMPAT_BASE_URL` |

## 工作循环

1. 在容器 `/home/claude/pai-next` 用 Write/Edit 写文件（快，无行数限制）。
2. 用 `mcp__remote-devices__device_commit_files` 把改动的文件同步到电脑：`stagedPath` 必须在 `/mnt/user-data/outputs/pai-next/...`（先 `cp -r /home/claude/pai-next/. /mnt/user-data/outputs/pai-next/`），`devicePath` 为 `D:\PaiCLI\pai-next\...`。一次 ≤ 50 个文件；只同步改过的。
3. 用 Desktop Commander `start_process` 在电脑上跑：`cd D:\PaiCLI\pai-next; mvn -B -ntp -q verify`（shell 用 `powershell.exe`；`timeout_ms` 给足，首次拉依赖可能 3–5 分钟；超时用 `read_process_output` 继续读）。
4. 看输出 → 回容器改 → 重复。
5. 阶段内按模块 `git add -A; git commit -m "..."`（在电脑上执行）。

## 规则

- Desktop Commander 只在 `D:\PaiCLI\pai-next` 内操作。不装软件、不改环境变量、不碰其他目录、不删除 `docs/`。
- **永远不要**打印、echo、写入或提交 API key；live 测试只通过 `$env:PAI_*` 读取。日志里出现 key 形态字符串视为严重缺陷。
- Windows 下 `mvn` 参数含 `=` 的用引号：`mvn verify "-DexcludedGroups=live"`。
- 文件统一 LF（`.gitattributes` 已设）；不要用 Desktop Commander 的 `write_file` 写源码（50 行限制、易出 CRLF），只用它跑命令和 `read_file` 看构建产物。
- 容器里 `mvn compile` 会因拉不到插件而失败——这是预期的，不要在容器里调试构建。
- 容器里有参考资料：pi 源码 `/tmp/claude-0/-home-claude/32b8a3cc-75d3-5f19-bc48-fd678f416075/scratchpad/ref/pi-mono`，阿里 p3c `…/ref/p3c`，旧仓库部分文件 `/mnt/user-data/uploads/PaiCLI/paicli/`（若不存在，用 `device_stage_files` 从 `D:\PaiCLI\paicli\...` 拉需要的文件；路径深度 ≤ 7 层）。
