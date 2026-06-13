# 调试日志埋点方案

> 目标：在 `claude-code-langchain4j` 关键执行链路补齐调试日志，让"一次对话/一次诊断"全程可观测、可回放、可定位。
>
> @author zhourui(V33215020)
> @since 2026-06-13

## 0. 现状结论（先核实，再下结论）

| 项 | 现状 |
|---|---|
| 日志门面 | SLF4J 2.0.13 |
| 日志实现 | Logback 1.5.6（仅 `cclc-cli` 引入实现） |
| 配置文件 | `cclc-cli/src/main/resources/logback.xml`：输出 `~/.claude-code-j/logs/cclc.log`，按天滚动，保留 7 天 / 200MB；root=INFO，`dev.langchain4j`/`dev.ai4j`=DEBUG |
| 已有日志 | 仅 `cclc-agent-diagnosis` 的 `interfaces/engine` 包 3 个类共 6 处调用：`DefaultDiagnoseEngine`(4)、`DiagnoseEngineBuilder`(1)、`StreamJsonHistoryParser`(1) |
| 缺口 | **`cclc-kernel` 业务代码 0 日志、`cclc-cli` 业务代码 0 日志、诊断工具层 0 日志**。核心主循环、LLM 流式、工具分派、权限决策、持久化全程黑盒 |

一句话：框架已就位，埋点几乎为零。本方案只做"加 logger 语句 + 完善配置"，不动架构。

## 1. 设计原则

1. **分层与日志依赖**：`domain` 层**允许依赖 SLF4J 门面（`slf4j-api`），但禁止依赖任何日志实现（logback 等）**。slf4j-api 是日志抽象，依赖它不破坏"domain 不绑定技术实现"的核心诉求（同理于 domain 依赖 JDK）。
   - 该约束在本项目**由构建天然保证**：domain / application / infrastructure 同在 `cclc-kernel` 模块，其 pom 中 `slf4j-api` 为 compile scope、`logback-classic` 为 **test scope**——main 编译期 classpath 只有门面，拿不到实现。ArchUnit 现状也只拦截 `dev.langchain4j.. / infrastructure / application / interfaces`，未限制 slf4j。
   - **默认仍倾向"在 application 边界记录、domain 内部少记"**：domain 关心规则成不成立，规则的语义（第几轮 / 哪个 session / 要不要中断）只有调用方完整，且带得上 MDC 上下文。domain 内部仅在出现**需要 trace 的复杂算法分支**时才直接打点；纯不变式 / 状态迁移不必。
2. **级别约定**（贯穿全项目）：
   - `ERROR`：未预期异常 + 堆栈、API 调用失败、IO 故障。
   - `WARN`：权限交互、降级（如 rg→Java 正则回退）、结果截断、超时、budget 触顶。
   - `INFO`：生命周期里程碑——启动、轮次进入/结束、LLM 一次往返、工具执行结果与耗时、权限决策、会话存取。**单轮对话看 INFO 即能还原骨架**。
   - `DEBUG`：入参/出参细节、stream delta、缓存断点、系统提示组成、配置明细。
   - `TRACE`：暂不启用。
3. **延迟求值**：统一用占位符 `log.debug("tool {} took {}ms", name, cost)`，禁止字符串拼接；大对象用 `if (log.isDebugEnabled())` 包裹。
4. **链路串联**：用 MDC 注入 `sessionId` / `turn`（轮次序号）/ `toolUseId`，在 `logback.xml` pattern 中输出，使并发虚拟线程的工具日志可归并到同一会话。
5. **脱敏红线**：禁止打印 `ANTHROPIC_API_KEY`、Token、完整文件内容、DB 连接串密码、Redis/Dubbo 凭据。文件类工具只记录**路径 + 行数/字节数**，不记录正文；SQL/命令记录**模板与参数摘要**，不记录潜在敏感值全文。

## 2. 埋点清单 —— cclc-kernel（核心引擎）

### 2.1 主循环 `application.AgentExecutor`
文件：`cclc-kernel/src/main/java/com/anthropic/cclc/application/AgentExecutor.java`

| 方法 | 级别 | 埋点内容 |
|---|---|---|
| `loop(...)`（top 入口，第 87 行） | INFO | 会话开始：messages 初始数、systemPrompt 长度；try/catch 兜底 ERROR 记异常 |
| `runLoop(...)`（第 99 行） | INFO | 每轮进入：`turn={}`、当前消息数；break 终止时记原因（无 tool_use / 取消 / budget 耗尽） |
| `executeTurn(...)`（第 120 行） | INFO | 本轮 LLM 调用前后；onComplete 收到 tool_use 数量；耗时 |
| `executeTurn` 内 StreamHandler.onUsage（第 136 行） | INFO | `inputTokens / outputTokens / cacheReadInputTokens`——cache 命中率关键指标 |
| `executeTurn` 内 onPartialText（第 132 行） | DEBUG | delta 长度累计（不逐 token 刷，按片段计数） |
| `executeTurn` 内 onError（第 141 行） | ERROR | LLM 流式失败异常 |
| `cancellationGuard`（第 161 行） | WARN | 检测到取消信号、在哪一轮中断 |

> MDC：`loop` 入口 `MDC.put("session", id)`，`runLoop` 每轮 `MDC.put("turn", n)`，finally 清理。

### 2.2 工具分派 `application.ParallelToolDispatcher`
文件：`cclc-kernel/.../application/ParallelToolDispatcher.java`

| 方法 | 级别 | 埋点内容 |
|---|---|---|
| `dispatch(...)`（第 38 行） | INFO | 本批工具名列表、并发数（=requests.size） |
| `runWithPermission(...)`（第 72 行） | INFO | 决策结果；DENY 时 WARN：`permission denied: {tool}` |
| `runTool(...)`（第 84 行） | INFO | 工具名 + 执行耗时 + 成功/失败；入参摘要走 DEBUG |
| `runTool` catch（第 90 行） | ERROR | 工具抛异常时的类型与消息（堆栈 DEBUG） |
| `assembleInOrder(...)`（第 113 行） | DEBUG | 验证回灌顺序与 tool_use 配对 |

> 在虚拟线程内执行前 `MDC.put("toolUseId", req.id())`，确保并发日志可区分。注意 MDC 默认不跨线程传播，需在提交 `Future` 前手动捕获父线程上下文并在子线程 set/clear。

### 2.3 权限 `application.PermissionService`
文件：`cclc-kernel/.../application/PermissionService.java`

| 方法 | 级别 | 埋点内容 |
|---|---|---|
| `check(...)` | INFO | tool 名 + 最终 Decision；命中 ALLOW_ALWAYS 缓存时 DEBUG |
| `askInteractively(...)` | WARN | 弹出交互提示、用户响应（ALLOW_ONCE / ALLOW_ALWAYS / DENY） |

### 2.4 系统提示 `application.SystemPromptComposer`
文件：`cclc-kernel/.../application/SystemPromptComposer.java`

| 方法 | 级别 | 埋点内容 |
|---|---|---|
| `compose(workingDir)` | DEBUG | 稳定前缀字符数、动态后缀内容（cwd / git status 摘要 / date）、是否合并了父级 CLAUDE.md |

### 2.5 LLM 客户端 `infrastructure.llm.LangChain4jLlmClient`
文件：`cclc-kernel/.../infrastructure/llm/LangChain4jLlmClient.java`

| 方法 | 级别 | 埋点内容 |
|---|---|---|
| `streamChat(request, handler)` | INFO | model、消息数、工具 schema 数；请求开始/结束 |
| `buildLcRequest(req)` | DEBUG | maxTokens、温度等参数；cache breakpoint 位置 |
| 流式异常分支 | ERROR | Anthropic 返回错误、网络异常 |

> `dev.langchain4j`/`dev.ai4j` 已配 DEBUG，会输出底层 HTTP；本层日志聚焦"我方语义"，避免与底层重复刷屏。

### 2.6 缓存断点 `infrastructure.llm.CacheBreakpointStrategy`
文件：`cclc-kernel/.../infrastructure/llm/CacheBreakpointStrategy.java`
- DEBUG：在哪几段插入 ephemeral 断点、稳定前缀边界——排查 cache 命中率下降时的首要现场。

### 2.7 持久化 `infrastructure.memory.FileChatMemoryStore`
文件：`cclc-kernel/.../infrastructure/memory/FileChatMemoryStore.java`

| 方法 | 级别 | 埋点内容 |
|---|---|---|
| `save(...)` | INFO | sessionId、写入消息行数、目标文件路径 |
| `load(...)` | INFO | sessionId、读取行数；单行反序列化失败 WARN（跳过/中断策略） |

### 2.8 领域不变式 `domain.conversation.ToolUseInvariantChecker`
- domain **可用 slf4j-api**，但此处属纯不变式校验，**默认不在 domain 内打点**。配对校验失败抛出的异常，由 application 层（`Conversation.append` 的调用方，即 `AgentExecutor` / `ParallelToolDispatcher`）在 catch 处以 ERROR 记录——这样能带上 MDC 的 session/turn 上下文，语义更完整。
- 仅当 domain 内出现需要 trace 的复杂分支时才直接用 `slf4j-api` 打 DEBUG；构建已保证 domain 编译期拿不到 logback 实现。

### 2.9 文件状态缓存 `infrastructure.tools.support.FileStateCache`
- WARN：read-before-write 守卫拦截（未读先写）、检测到外部 mtime 变更——这是 claude-code parity 的高频报错点，必须可见。

## 3. 埋点清单 —— 各 Tool 实现（infrastructure.tools）

统一约定：每个 `execute(args, ctx)` 入口 DEBUG 记入参摘要，出口 INFO 记结果规模 + 耗时，异常 ERROR。具体差异：

| 工具 | 文件 | 专项埋点 |
|---|---|---|
| `BashTool` | `tools/BashTool.java` | INFO：选中的 shell（cmd.exe / bash）、退出码、耗时；DEBUG：命令模板（脱敏）；超时 WARN |
| `FileReadTool` | `tools/FileReadTool.java` | INFO：路径 + 读取行数/字节；DEBUG：offset/limit。**不打印正文** |
| `FileWriteTool` | `tools/FileWriteTool.java` | INFO：路径 + 写入字节数 + 新建/覆盖；read-before-write 拦截 WARN |
| `FileEditTool` | `tools/FileEditTool.java` | INFO：路径 + 替换次数；old/new 文本走 DEBUG 且截断 |
| `GlobTool` | `tools/GlobTool.java` | INFO：pattern + 命中文件数 + 耗时 |
| `GrepTool` | `tools/GrepTool.java` | INFO：pattern + 后端（ripgrep / JavaRegex）+ 命中数；**回退到 Java 正则时 WARN** |
| `SkillTool` | `tools/SkillTool.java` | INFO：skill 名 + 是否命中 SKILL.md；未找到 WARN |
| `SubAgentTool` | `tools/SubAgentTool.java` | INFO：子 agent 启动/结束 + 子轮次数 |
| `StructuredOutputTool` | `tools/StructuredOutputTool.java` | DEBUG：schema 校验通过/失败明细 |

## 4. 埋点清单 —— cclc-cli（接口层）

| 类 | 文件 | 级别 | 埋点内容 |
|---|---|---|---|
| `CclcApplication.run()` | `interfaces/cli/CclcApplication.java` | INFO | 启动：model、permissionMode、skillsDir 是否启用、注册工具数（**不打 apiKey**） |
| `CclcApplication.main()` | 同上 | ERROR | 配置加载失败、致命启动异常（当前是 `System.err`，改为 logger + 保留用户可见提示） |
| `ReplLoop.run()` | `interfaces/cli/ReplLoop.java` | DEBUG | 每次用户输入长度、是否多行/代码块 |
| `SlashCommandParser.parse()` | `interfaces/cli/SlashCommandParser.java` | INFO | 识别到的斜杠命令名 + args；未知命令 WARN |
| `SigintHandler` | `interfaces/cli/SigintHandler.java` | WARN | 第一次 Ctrl-C（取消 turn）、第二次（退出进程） |
| `SessionResumer` | `application/SessionResumer.java` | INFO | `/resume` 加载的 sessionId、恢复消息数（强调不重跑工具） |

> 注意：`OutputRenderer` 是面向用户的终端渲染，**不要把渲染内容重复写日志**，只在渲染异常时 ERROR。

## 5. 埋点清单 —— cclc-agent-diagnosis（诊断引擎）

该模块已是 agent-web 进程内引擎，已有少量 engine 层日志，需向下补齐 application 与工具层。

### 5.1 引擎入口（已有部分日志，补齐）
| 类 | 文件 | 级别 | 埋点内容 |
|---|---|---|---|
| `DefaultDiagnoseEngine.run()` | `interfaces/engine/DefaultDiagnoseEngine.java` | INFO | sessionId、是否复用历史、turn 开始/结束、最终 exitCode（已有 4 处，校验覆盖 stop/isRunning/close） |
| `DiagnoseEngineBuilder` | `interfaces/engine/DiagnoseEngineBuilder.java` | INFO | 装配的工具集、超时/budget 配置（已有 1 处） |
| `StreamJsonHistoryParser` | `interfaces/engine/StreamJsonHistoryParser.java` | WARN | 解析失败的行、跳过策略（已有 1 处） |

### 5.2 诊断应用层（application.diagnosis，当前 0 日志）
| 类 | 文件 | 级别 | 埋点内容 |
|---|---|---|---|
| `DiagnosisPlanner` | `application/diagnosis/DiagnosisPlanner.java` | INFO | 生成的诊断步骤数、假设数 |
| `DiagnosisTaskRunner` | `application/diagnosis/DiagnosisTaskRunner.java` | INFO | taskType、关联 hypothesis、执行结果；失败 WARN |
| `DiagnosisReporter` | `application/diagnosis/DiagnosisReporter.java` | INFO | 根因候选数、置信度、报告校验通过/失败 |
| `PlanGuardPolicy` | `application/diagnosis/PlanGuardPolicy.java` | WARN | guard 拦截（越权/超范围）的动作 |

### 5.3 诊断只读工具（infrastructure.tools，当前 0 日志）
统一：execute 入口 DEBUG 记查询条件，出口 INFO 记返回行数/字节 + 耗时，外部调用失败 ERROR，被截断 WARN。

| 工具 | 文件 | 专项埋点 |
|---|---|---|
| `LogQueryTool` | `tools/LogQueryTool.java` | traceId / 关键词 / 时间窗 / psa；命中条数 |
| `EsReadTool` | `tools/EsReadTool.java` | 索引名、DSL 摘要、took、命中数 |
| `MysqlReadTool` | `tools/MysqlReadTool.java` | 库表、SQL 模板（**脱敏**）、返回行数 |
| `RedisReadTool` | `tools/RedisReadTool.java` | key/pattern、类型、TTL |
| `DubboInvokeTool` | `tools/DubboInvokeTool.java` | 接口 + 方法、provider 地址、耗时；连接失败 ERROR |
| `HttpGetTool` | `tools/HttpGetTool.java` | URL（脱敏 query）、HTTP 状态码、响应字节 |
| support/* Client | `tools/support/*Client.java` | 底层连接建立/关闭、socket 超时、重试——排障最底层现场，DEBUG 为主、失败 ERROR |

## 6. 配置改造（logback.xml）

文件：`cclc-cli/src/main/resources/logback.xml`

1. **pattern 加 MDC**：
   ```
   %d{HH:mm:ss.SSS} %-5level [%X{session:-};t%X{turn:-};%X{toolUseId:-}] %logger{36} - %msg%n
   ```
2. **分包级别开关**（默认 INFO，排障时调 DEBUG，无需改代码）：
   ```xml
   <logger name="com.anthropic.cclc.application" level="INFO"/>
   <logger name="com.anthropic.cclc.infrastructure.tools" level="INFO"/>
   <logger name="com.anthropic.cclc.infrastructure.llm" level="INFO"/>
   <logger name="com.anthropic.cclc.infrastructure.diagnosis" level="INFO"/>
   ```
3. **环境变量驱动级别**：用 `${CCLC_LOG_LEVEL:-INFO}` 让用户一键开 DEBUG，对齐现有 `CCLC_*` 配置风格。
4. **诊断模块独立 appender（可选）**：`cclc-agent-diagnosis` 作为进程内 jar 被 agent-web 依赖，宿主可能有自己的 logback。建议该模块**不自带 logback 实现依赖**（保持只依赖 slf4j-api，现状如此），日志配置交由宿主；本仓库测试用 `test` scope 的 logback。
5. **控制台 vs 文件**：当前只有 FILE appender。REPL 是交互式终端，日志写文件即可（避免污染对话输出）；保持现状，**不加 STDOUT appender**。

## 7. 落地步骤（建议顺序）

1. 改 `logback.xml`：加 MDC pattern、分包 logger、`CCLC_LOG_LEVEL`。（纯配置，先行）
2. kernel 主链路：`AgentExecutor` → `ParallelToolDispatcher` → `PermissionService` → `LangChain4jLlmClient`，含 MDC 注入与跨虚拟线程传播。
3. kernel 工具层 + 持久化 + FileStateCache。
4. cli 接口层。
5. diagnosis application + 工具层 + support client。
6. 自检：跑一次真实对话（`mvn exec:java`）与一次诊断，确认 `~/.claude-code-j/logs/cclc.log` 中能按 session/turn 还原完整链路，且无敏感信息泄漏。

## 8. 注意事项小结

- domain 允许用 slf4j-api（构建保证拿不到实现），但默认在 application 边界记录、domain 内部少记；纯不变式异常在边界 ERROR。
- MDC 不跨线程自动传播，`ParallelToolDispatcher` 提交虚拟线程任务前必须手动搬运上下文。
- 工具入参/正文一律脱敏 + 截断，文件类只记元数据。
- 不重复底层（LC4J HTTP 已 DEBUG）与用户可见输出（OutputRenderer）。
- 级别即语义：单轮对话看 INFO 还原骨架，深挖再开 DEBUG。
