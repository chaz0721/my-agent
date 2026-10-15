# pai-next 代码风格规范（基于《阿里巴巴 Java 开发手册》黄山版）

> 基准：**《Java 开发手册（黄山版）》2022.02**（alibaba/p3c）。手册分【强制】【推荐】【参考】三级：本项目**全部【强制】照办**，【推荐】默认照办，【参考】按 §4 的偏离表处理。
> 原则不变：能让工具强制的，不写成文字；写成文字的，必须有例子。实施 session 在 M1 建骨架时必须先接入 §1 的工具链。
> 手册原文以 PDF 为准（仓库 `p3c` 根目录 `Java开发手册(黄山版).pdf`），本文只摘与本项目相关的条目并标注章节。

## 1. 构建强制层

### 1.1 格式化 — Spotless + p3c Eclipse 格式模板

手册编程规约 §五"代码格式"：4 空格缩进、不用 tab、单行 ≤ 120 字符、K&R 大括号、运算符两侧空格、方法间一空行。阿里官方提供的 Eclipse 格式模板 `p3c-formatter/eclipse-codestyle.xml` 已实现这些（lineSplit=120、4 空格）。

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId><artifactId>spotless-maven-plugin</artifactId>
  <configuration><java>
    <eclipse><version>4.32</version><file>${maven.multiModuleProjectDirectory}/build/p3c-eclipse-codestyle.xml</file></eclipse>
    <removeUnusedImports/>
    <importOrder><order>java,javax,org,com,dev.pai,,\#</order></importOrder>   <!-- 手册：静态导入放最后；不允许通配 import -->
    <licenseHeader><content>/* pai-next — Apache-2.0 */</content></licenseHeader>
  </java></configuration>
  <executions><execution><goals><goal>check</goal></goals><phase>verify</phase></execution></executions>
</plugin>
```
- `build/p3c-eclipse-codestyle.xml` 从 `https://github.com/alibaba/p3c/blob/master/p3c-formatter/eclipse-codestyle.xml` 复制入库（锁版本，不在线拉）。IDEA 用户另装 **Alibaba Java Coding Guidelines** 插件 + 导入 `p3c-formatter/intellij-java-code-style.xml`（若该文件存在于对应版本；否则用 Eclipse 模板导入）。
- `.editorconfig`：UTF-8、LF、4 空格、`max_line_length=120`、末尾换行、去尾空格。
- 本地 `mvn spotless:apply` 一键修复。格式不进入 code review 讨论。

### 1.2 手册规约检查 — P3C-PMD（有已知限制，见下）

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId><artifactId>maven-pmd-plugin</artifactId>
  <configuration>
    <rulesets>
      <ruleset>rulesets/java/ali-comment.xml</ruleset><ruleset>rulesets/java/ali-concurrent.xml</ruleset>
      <ruleset>rulesets/java/ali-constant.xml</ruleset><ruleset>rulesets/java/ali-exception.xml</ruleset>
      <ruleset>rulesets/java/ali-flowcontrol.xml</ruleset><ruleset>rulesets/java/ali-naming.xml</ruleset>
      <ruleset>rulesets/java/ali-oop.xml</ruleset><ruleset>rulesets/java/ali-orm.xml</ruleset>
      <ruleset>rulesets/java/ali-other.xml</ruleset><ruleset>rulesets/java/ali-set.xml</ruleset>
    </rulesets>
    <printFailingErrors>true</printFailingErrors><failOnViolation>false</failOnViolation><minimumPriority>2</minimumPriority>
  </configuration>
  <dependencies><dependency><groupId>com.alibaba.p3c</groupId><artifactId>p3c-pmd</artifactId><version>2.1.1</version></dependency></dependencies>
</plugin>
```

**已知限制**：`p3c-pmd 2.1.1` 基于 **PMD 6.15.0**（2019），不认识 record / sealed / switch 模式匹配 / 文本块。所以直接配成**报告型**（`failOnViolation=false`），解析失败的文件加 `<excludes>`；`mvn pmd:check` 的输出贴进阶段报告，审查时看。阿里若发布基于 PMD 7 的版本再切 blocking。手册【强制】条目在此之前靠 ArchUnit（能自动化的）+ 审查（其余）。

### 1.3 编译期检查 — Error Prone + NullAway（**M1 不接，M2 再评估**）

个人开发阶段不接。若 M2 后发现 null 相关 bug 或 `equals`/字符集类低级错误反复出现，再加 Error Prone（含 NullAway）。届时参照手册条目：`MissingOverride`、`EqualsIncompatibleType`、`DefaultCharset`、`JavaTimeDefaultTimeZone`、`CatchAndPrintStackTrace`、`SystemOut`。

在此之前，"公开 API 不返回 null、用 Optional/空集合"是审查约定（§3.7）。

### 1.4 测试与覆盖率

- Surefire 默认跑；`skipTests` 不得出现在 pom（手册单元测试规约【强制】AIR 原则、可重复执行）。JaCoCo 只出报告不设门槛，审查时看核心模块是否明显偏低。
- 测试用 `@TempDir`，不读写用户目录；不依赖执行顺序（手册【强制】测试用例间不得相互依赖）。
- `@Tag("live")` 真实 provider；`@Tag("slow")` 子进程/网络 mock；默认 `excludedGroups=live`。

### 1.5 架构 — ArchUnit

见 `00-architecture.md` §2 五条依赖规则。另加手册相关的可自动化条目：

| ArchUnit 规则 | 手册条目 |
|---|---|
| 抽象类以 `Abstract`/`Base` 开头；异常类以 `Exception` 结尾；测试类以 `Test` 结尾 | 命名【强制】 |
| 所有 `enum` 类名以 `Enum` 结尾 | 命名【参考】→ 本项目强制 |
| 实现 `Provider`/`SessionStore`/`Renderer`/`Summarizer`/`TokenEstimator`/`Approver`/`McpTransport` 的具体类以 `Impl` 结尾 | 命名【参考】→ 本项目强制（范围见 §3.1） |
| 包名全小写、单数、单词间不用分隔 | 命名【强制】 |
| 常量类/接口中的 `static final` 字段全大写下划线 | 命名【强制】 |
| 禁止 `Util`/`Utils`/`Helper`/`Legacy`/`Adapter`/`Bridge` 作类名后缀（`Manager` 仅 `McpServerManager`） | 本项目病史，见 §4 |
| `pai-model`/`pai-core` 的 public 类型只能是 `interface | record | enum | sealed interface | final class` | 本项目规则（手册 OOP【推荐】类成员可见性最小化的延伸） |
| 禁止非 `final` 的 `static` 字段 | 并发【强制】避免共享可变静态状态 |
| `pai-model`/`pai-core` 不得 import `java.util.Date`、`SimpleDateFormat`、`System.out` | 日期时间【强制】、日志【强制】 |

### 1.6 依赖 — Enforcer

`requireJavaVersion [21,)`、`banDuplicatePomDependencyVersions`。**banned**：`okhttp*`、`lombok`、`guava`、`commons-lang3`、`spring-*`、`reactor-*`、以及**直接**依赖 `logback-classic`/`log4j-core` 于 `pai-model`/`pai-core`/`pai-tools`/`pai-ext-*`（手册日志规约【强制】只依赖 SLF4J 门面；绑定只允许出现在 `pai-cli`）。

## 2. 日志（手册日志规约，与 M1 规格的差异）

手册【强制】"应用中不可直接使用日志系统的 API，应使用 SLF4J 门面"。因此 **修订 M1 规格**：核心层用 `org.slf4j.Logger`（`slf4j-api` 2.x），不再用 `System.Logger`；`pai-cli` 选一个绑定（建议 `slf4j-simple` 或 `logback-classic`，仅 cli）。其余照手册：
- `private static final Logger log = LoggerFactory.getLogger(Xxx.class);`
- 占位符 `{}`，不拼接、不在 `debug` 前做昂贵计算（用 `isDebugEnabled` 或 lambda）。
- 异常日志必须带 `e`：`log.warn("mcp server {} failed to start", name, e)`。
- 不记录密钥、完整 prompt、文件全文；`PAI_DEBUG_HTTP=1` 专用路径脱敏 `Authorization`。
- 日志内容是给开发者的，不含中文、不含用户文案。

## 3. 审查约定（摘手册 + 本项目补充，均有例子）

### 3.1 命名（手册 §一·1 命名风格）
- 类 UpperCamelCase；方法/变量 lowerCamelCase；常量全大写下划线且**语义完整**（`MAX_TOOL_CONCURRENCY`，不是 `MAX`）。
- 布尔属性不加 `is` 前缀（record 字段 `allowed` 而非 `isAllowed`；方法可以 `isAllowed()`）。
- 不用拼音、不用缩写（除 `id/url/uri/json/http/mcp/sse` 等公认缩写）；杜绝不规范缩写（`condition` 不写 `condi`）。
- 接口方法不加 `public`、不加修饰；接口中不定义变量（常量另放 record/enum）。
- 枚举成员全大写下划线，带注释说明用途。**枚举类名带 `Enum` 后缀**（手册【参考】，本项目采纳为强制，ArchUnit 检查）：`StopReasonEnum`、`ToolEffectEnum`、`CapabilityEnum`。
- **实现类带 `Impl` 后缀**（手册【参考】，本项目采纳，ArchUnit 检查），适用范围：实现核心 *服务型接口* 的类——`Provider`、`SessionStore`、`Renderer`、`Summarizer`、`TokenEstimator`、`Approver`、`McpTransport`、`SessionAccess`。允许加描述前缀：`JsonlSessionStoreImpl`、`AnthropicProviderImpl`、`StdioTransportImpl`。**不适用**：`Tool`、`Extension`、`AgentHooks`、`SlashCommand` 的实现（它们是插件式的多实例，按行为命名，如 `ReadTool`、`McpExtension`、`CompactingHooks`）——手册该条本意针对 Service/DAO 的接口-实现对，不是所有接口。
- 测试方法用下划线句子：`blocks_tool_when_any_hook_blocks()`（手册未约束，本项目约定）。

### 3.2 常量（手册 §一·2）
- 【强制】不允许魔法值：`if (retries > 3)` → `MAX_RETRIES`。
- long 用 `L` 大写。
- 常量按功能归类到所属类型内（`CompactionPolicy.DEFAULT_RESERVE`），不建"一个 `Constants` 类"。

### 3.3 OOP（手册 §一·6）
- 【强制】`equals` 常量在前：`"deepseek".equals(id)`；包装类用 `equals`；浮点用 `BigDecimal`/误差比较。
- 【强制】所有 POJO 属性用包装类型？——**偏离**：本项目用 `record`，字段非空由 NullAway 保证，基本类型直接用（见 §4）。
- 【强制】不得在构造方法里做业务逻辑；record 紧凑构造器只做校验与 `List.copyOf`。
- 【推荐】`toString` 用 record 自带；不手写。
- 【推荐】类成员顺序：公有→保护→私有；静态→实例；构造→方法；`getter/setter` 放最后（record 无此问题）。
- 本项目补充：不变性默认（字段 `private final`、集合 `copyOf`、不暴露可变集合）；不写继承层级（`sealed` + record 代替），无 `Abstract*` 类除非 ADR 允许。

```java
public record ToolResult(List<ContentBlock> content, boolean isError, Optional<JsonNode> details) {
    public ToolResult {
        content = List.copyOf(content);                    // 手册：集合不可被外部修改
        Objects.requireNonNull(details, "details");
    }
}
```

### 3.4 集合（手册 §一·7）
- 【强制】判空用 `isEmpty()`；`toArray` 传有类型的数组；`Arrays.asList`/`List.of` 不可增删；`foreach` 内不 `remove`。
- 【强制】返回空集合而非 null；Map 的 key/value 是否允许 null 要清楚（`ConcurrentHashMap` 不允许）。
- 【推荐】初始化 `HashMap` 给容量；使用 `entrySet` 遍历。
- 【推荐】集合泛型用 `<>`，不留 raw type。

### 3.5 并发（手册 §一·8）
- 【强制】线程/线程池命名有意义（`Thread.ofVirtual().name("pai-tool-", 0)`）。
- 【强制】不用 `Executors` 创建线程池——**偏离**：允许且推荐 `Executors.newVirtualThreadPerTaskExecutor()`（见 §4）；平台线程池如需要，按手册用 `ThreadPoolExecutor` 显式参数。
- 【强制】`SimpleDateFormat` 非线程安全——不用；用 `java.time`。
- 【强制】高并发下避免长锁；资源加锁顺序一致；`lock()` 在 `try` 外。
- 本项目补充：回调在调用方线程同步执行并写进 Javadoc；`CancelToken` 协作式检查。

### 3.6 控制语句（手册 §一·9）
- 【强制】`switch` 每个 case 有 `break/return` 或注释说明 fallthrough；**本项目**：一律用箭头形式；对 sealed 类型不写 `default`（编译器穷尽检查）。
- 【强制】`if/else/for/while` 必须用大括号，哪怕一行。
- 【推荐】表达异常分支时少用 `if-else`，用卫语句；嵌套不超过 3 层。
- 【推荐】不在条件判断里执行复杂语句，先赋给有名字的布尔变量。

### 3.7 异常（手册 §二·1）
- 【强制】不用异常做流程控制；能预检的（`null`/下标/状态）不要靠 `catch`。
- 【强制】`catch` 要区分异常类型；禁止 `catch (Exception e) {}` 空捕获、禁止 `catch Throwable`。
- 【强制】捕获后要么处理、要么带 cause 抛给上层、要么记日志（含 `e`）；`finally` 里不 `return`。
- 【强制】资源对象用 try-with-resources。
- 【推荐】不定义"通用异常基类"（手册：自定义异常要有业务含义）；本项目：预期内失败用返回值（`ToolResult.isError`、`StreamEvent.Error`），契约违反抛 `IllegalArgumentException/IllegalStateException`，I/O 抛 `UncheckedIOException` 或在边界转返回值。
- 【推荐】方法返回值可以为 null 时必须注释说明——本项目更严：公开 API 不返回 null（NullAway）。

### 3.8 注释（手册 §一·11）
- 【强制】类、类属性、公开方法用 Javadoc `/** */`，不用 `//`；类注释写**职责**一句话（可加 `@author` 省略）。
- 【强制】枚举成员必须注释。
- 【强制】修改代码同步修改注释；注释不说明"做了什么"而说明"为什么"。
- 【推荐】`TODO` 必须有标记人与时间——本项目更严：**不许进主分支**，写进报告"建议"节。
- 注释语言：**英文**（核心层不许出现中文字面量；注释也统一英文，避免 Javadoc 中英混杂）。

### 3.9 尺寸与结构（手册【推荐】方法 ≤ 80 行；本项目收紧）
- 文件一个顶层类型（record/enum 嵌在 sealed 接口内除外）。
- 类 ≤ 400 行、方法 ≤ 60 行、参数 ≤ 5（多了用 record 参数对象）。
- 单行 ≤ 120 字符（格式器强制）。

### 3.10 测试（手册 §三 单元测试）
- 【强制】AIR：自动、独立、可重复；不依赖外部网络/环境；`@TempDir`。
- 【强制】断言必须具体（AssertJ），不用 `System.out` 肉眼验证。
- 【推荐】BCDE：Border、Correct、Design、Error 四类用例都要覆盖；本项目的 Codec fixtures 命名要体现场景。
- 本项目：不 mock 自己的 record/sealed 类型；Mockito 仅 `pai-tui`；时间注入 `Clock`。

## 4. 对手册的明确偏离（都需理由）

| 手册条目 | 级别 | 本项目做法 | 理由 |
|---|---|---|---|
| POJO 属性必须用包装类型 | 强制 | record 字段可用基本类型 | 该条解决的是 ORM 映射 null 问题；本项目无 ORM，非空由 NullAway 保证，record 不是 POJO |
| 不允许用 `Executors` 创建线程池 | 强制 | 允许 `newVirtualThreadPerTaskExecutor()` | 该条防的是无界队列 OOM；虚拟线程 executor 无队列、每任务一线程，不适用。平台线程池仍按手册 |
| 日志用 SLF4J 门面 | 强制 | **采纳**（修订 M1：`System.Logger` → `slf4j-api`） | — |
| 禁止 `Util/Utils/Helper/Manager/Legacy/Adapter/Bridge` 类名 | （手册无） | 本项目禁 | 旧仓库病史：这些名字是职责不清和兼容层的温床 |
| 方法 ≤ 80 行 | 推荐 | 收紧到 60 | 配合 record/sealed 的小类风格 |
| P3C-PMD 作为门禁 | （工具） | 报告型 | PMD 6.15 不解析 Java 21 语法，见 §1.2 |

增加偏离项必须：在此表加一行 + 说明理由 + 若影响公开签名则同步 ADR/规格。

## 5. 对实施 session 的要求

- M1 建骨架：先接 Spotless（p3c 模板）、ArchUnit、Enforcer、Surefire 默认开，P3C-PMD 以报告型接入；空工程 `mvn verify` 通过，再写业务代码。Error Prone/NullAway 不接。
- 把 P3C 实际状态（blocking/reporting、排除文件数、PMD 版本）写进 M1 报告。
- 提交前固定动作：`mvn spotless:apply && mvn -q verify`。
- 审查按 §3 逐节抽查，违反的修复后再合，不解释"后面再改"。
