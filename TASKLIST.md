# AgentKit on LangChain4j — 任务清单

> 本文档由 `DESIGN.md` 与 Agent Runtime 对照审计拆解而来，共 **55 个任务**，覆盖 11 个阶段。
>
> **TDD 任务**遵循 Red → Green → Refactor 循环；**Infra 任务**只给目标 + 验收。
> 每个任务的 DoD（Definition of Done）包含编码规范红线（函数 ≤50 行、嵌套 ≤3 层、命名自解释）。

## 阶段总览

| 阶段 | 任务 ID | 工期估算 | 关键交付 |
|---|---|---|---|
| S0 脚手架 | #1–3 | 0.5d | Maven + DDD 目录 + CI |
| S1 Domain 骨架 | #4–8 | 1d | ChatMessage / Conversation / Tool / ToolInvocation / TokenBudget |
| S2 LLM 端口 + Executor | #9–13 | 1d | StubLlmClient + AgentExecutor 主循环 |
| S3 LangChain4j 接入 | #14–18 | 1d | Anthropic 真实 API + prompt cache |
| S4 内置工具 | #19–25 | 2d | Bash / FileRead / FileWrite / FileEdit / Glob / Grep / FileStateCache |
| S5 权限系统 | #26–28 | 1d | 4 模式 Policy + 交互 prompt + Executor 拦截 |
| S6 上下文 + REPL | #29–32 | 1d | ContextProvider + 系统提示 + JLine REPL + 斜杠命令 |
| S7 中断 + 流式渲染 | #33–35 | 0.5d | CancellationToken + SIGINT + 实时 token 输出 |
| S8 持久化 | #36–38 | 0.5d | JSONL store + /resume + SessionId |
| MVP-Gate | #39 | — | 端到端冒烟，打 `v0.1.0-mvp` |
| S9 Runtime Hardening | #40–46 | 已完成（7/7） | run scope、tool batch、terminal、安全、取消、上下文、恢复 |
| S10 Runtime Extensions | #47–55 | 进行中（3/9） | 子 Agent、interceptor、MCP、后台任务、暂停恢复、分支、模型策略、manifest、CLI |
| **MVP 合计** | — | **≈8 人天** | — |

## 依赖图

```
S0 (1→2→3)
  ├→ S1 (4→5, 4→6→7, 8)
  │    ├→ S2 (9→10→11→12→13)         9 ← 4,7    10 ← 5,6,9    13 ← 33
  │    │    └→ S3 (15→16→17→18)     14 并行     16 ← 14,15
  │    ├→ S4 (19,20,21,22,23,24 ← 25, 6)
  │    ├→ S5 (26→27→28)              28 ← 13,19,27
  │    ├→ S6 (29→30, 31→32)          30 ← 17,29
  │    ├→ S7 (33→34, 35)             34 ← 31,33    35 ← 31
  │    └→ S8 (36, 38 ← 14, 37 ← 32,36)
  │                                            ↓
  │                                   MVP-Gate (#39)
  │                                            ↓
  └→ S9 (#40→41→42；40→43；40,42→44→45→46)
                                               ↓
     S10 (#47→50；#48→49；#47→54→55；#51/#52/#53 并行支线)
```

注：#33 `CancellationToken` 被 #13 引用，应在 S1 后立即做，避免阻塞 S2。

---

## 任务详情

### S0 脚手架

#### #1 [S0-Infra] 初始化 Maven 项目骨架与依赖

**Goal**: 建立可编译的空项目。

**步骤**:
- 创建 `pom.xml`：JDK 21、UTF-8、jacoco、surefire
- 依赖：langchain4j-anthropic、langchain4j-mcp、picocli、jline、jackson-databind、zt-exec、slf4j+logback、junit5、assertj、mockito
- `AgentKitApplication.main` 空壳，打印版本号

**DoD**: `mvn clean verify` 通过；`mvn -q -pl agentkit-cli -am test-compile exec:java "-Dexec.args=--version"` 输出版本号。

---

#### #2 [S0-Infra] 建立 DDD 四层目录骨架 （blockedBy: 1）

**Goal**: 落地 DESIGN.md §11 的包结构。

**步骤**:
- 按 `interfaces/cli`、`application`、`domain/{conversation,message,tool,permission,context,port}`、`infrastructure/{llm,tools,memory,context,permission,mcp}` 建空包，每包放 `package-info.java`
- ArchUnit 测试：禁止 domain 引用 langchain4j / infrastructure

**DoD**: `mvn test` 通过 ArchUnit 规则。

---

#### #3 [S0-Infra] CI 占位（mvn verify）（blockedBy: 2）

**Goal**: 提交即跑测试。

**步骤**:
- `.github/workflows/ci.yml`：push/PR 触发 `mvn -B verify`
- README 加状态徽章占位

**DoD**: 本地 `act` 或 push 后 CI 绿。

---

### S1 Domain 骨架

#### #4 [S1-TDD] ChatMessage 类型层级建模 （blockedBy: 3）

**Red**: `ChatMessageTest` — 断言 `UserMessage.of("hi").role() == USER`、`AiMessage` 可携带 `toolUseRequests`、`ToolResultMessage` 必须关联 `ToolUseId`。

**Green**: 用 sealed interface + record 实现四种消息类型。

**Refactor**: 抽取 `ChatMessage` 公共方法到 sealed 接口；消除 record 字段重复。

**DoD**: 单元测试覆盖 4 种类型；每类 ≤50 行。

---

#### #5 [S1-TDD] Conversation 顺序追加与不变式 （blockedBy: 4）

**Red**: `ConversationTest`
- `appendsMessagesInOrder` — 追加顺序与读取顺序一致
- `rejectsToolResultWithoutMatchingToolUse` — 抛 `IllegalArgumentException`
- `rejectsToolResultForAlreadySettledToolUse` — 同一 `ToolUseId` 不能 settle 两次

**Green**: `Conversation` 维护 `List<ChatMessage>` + `Set<ToolUseId> pendingToolUseIds`；append 时校验。

**Refactor**: 提取 `ToolUseInvariantChecker` 为独立类，避免 Conversation 膨胀。

**DoD**: 3 个测试绿；Conversation 类 ≤200 行。

---

#### #6 [S1-TDD] Tool 接口与 ToolRegistry 查找 （blockedBy: 3）

**Red**: `ToolRegistryTest`
- `findsToolByName` — 注册后能查到
- `throwsOnUnknownTool` — 未知工具抛 `UnknownToolException`
- `rejectsDuplicateRegistration` — 同名工具注册两次抛错

**Green**: `ToolRegistry` 用 `Map<String, Tool>`；`Tool` 接口含 `name/description/inputSchema/isReadOnly/execute`。

**Refactor**: `ToolArguments` 封装为 record，避免 `Map<String, Object>` 裸露。

**DoD**: 3 个测试绿。

---

#### #7 [S1-TDD] ToolInvocation 状态机 （blockedBy: 6）

**Red**: `ToolInvocationTest`
- `startsAsPending` — 新建后状态为 PENDING
- `transitionsAllowedToCompleted` — 经 ALLOWED → COMPLETED 合法
- `cannotCompleteWithoutPermissionDecision` — 跳过权限抛错

**Green**: enum `InvocationState { PENDING, ALLOWED, DENIED, COMPLETED, FAILED }` + 状态迁移守卫。

**Refactor**: 用 sealed interface 表达状态而非 enum + 字段，杜绝非法态。

**DoD**: 状态迁移矩阵测试齐全。

---

#### #8 [S1-TDD] TokenBudget 估算与阈值检查 （blockedBy: 3）

**Red**: `TokenBudgetTest`
- `estimatesByCharHeuristic` — 用 chars/4 粗估
- `flagsThresholdReached` — 超过 85% 阈值返回 true

**Green**: `TokenBudget` 内联字符估算，不引入 tokenizer 依赖。

**Refactor**: 抽 `TokenEstimator` 接口，便于 P1 接入真实 tokenizer。

**DoD**: 2 个测试绿。

---

### S2 LLM 端口 + Executor

#### #9 [S2-TDD] LlmClient 端口与 StubLlmClient （blockedBy: 4, 7）

**Red**: `StubLlmClientTest`
- `replaysPreconfiguredResponsesInOrder` — 按队列返回
- `failsWhenExhausted` — 队列空时抛 `AssertionError`

**Green**: `LlmClient` 接口 + `StubLlmClient` 实现，支持注入 `Queue<AiMessage>`。

**Refactor**: `StreamHandler` 提取为 inner interface；区分 `onPartialText`/`onComplete`/`onError`。

**DoD**: Stub 可在后续 Executor 测试中直接复用。

---

#### #10 [S2-TDD] AgentExecutor 无 tool 时单轮终止 （blockedBy: 5, 6, 9）

**Red**: `AgentExecutorTest.stopsWhenAssistantHasNoToolUse`
- Stub 返回一条纯文本 AiMessage
- 断言：LLM 被调用 1 次；Conversation 末尾是 AiMessage；返回值文本匹配

**Green**: while 循环判断 `aiMessage.hasToolUseRequests() == false` 即 break。

**Refactor**: 主循环抽取 `executeTurn(...)` 私有方法保持 `run()` ≤50 行。

**DoD**: 测试绿；run() 方法 ≤50 行。

---

#### #11 [S2-TDD] AgentExecutor 单 tool 调用并回灌结果 （blockedBy: 10）

**Red**: `AgentExecutorTest.executesToolAndFeedsResultBackToModel`
- Stub 第一次返回带 `tool_use(Bash, ls)`，第二次返回纯文本
- 注入 `FakeBashTool` 返回 "file.txt"
- 断言：工具被调用 1 次；conversation 含 ToolResultMessage("file.txt")；第二次 LLM 调用收到该 result

**Green**: 提取 `dispatchToolCalls`，单工具同步执行 → append result → 继续循环。

**Refactor**: `ToolRequest → ToolInvocation` 的转换抽到 `InvocationFactory`。

**DoD**: 测试绿。

---

#### #12 [S2-TDD] AgentExecutor 并发执行多 tool 保序回灌 （blockedBy: 11）

**Red**: `AgentExecutorTest.parallelToolsReturnInOriginalOrder`
- Stub 返回 3 个 tool_use；FakeTool 用不同 sleep 模拟乱序完成
- 断言：3 个 ToolResultMessage 在 conversation 中的顺序与 tool_use 顺序一致

**Green**: 用虚拟线程 `Executors.newVirtualThreadPerTaskExecutor()` 并发；`ConcurrentHashMap<ToolUseId, Result>` 聚合；按 tool_use 顺序回填。

**Refactor**: 抽 `ParallelToolDispatcher` 类；保留单工具快路径避免线程开销。

**DoD**: 顺序断言通过；并发场景下无竞态测试 100 次稳定。

---

#### #13 [S2-TDD] AgentExecutor 响应 CancellationToken （blockedBy: 12, 33）

**Red**: `AgentExecutorTest.exitsLoopWhenCancelled`
- Stub 在第二轮前外部触发 cancel
- 断言：抛 `CancellationException` 或返回部分结果；LLM 调用 ≤1 次

**Green**: `CancellationToken` 持 volatile boolean；run() 每轮起点检查；流式 handler 内 onPartial 检查。

**Refactor**: 提取 `cancellationGuard()` 卫语句。

**DoD**: 取消测试绿；正常路径回归不受影响。

---

### S3 LangChain4j 接入

#### #14 [S3-Infra] 配置加载（env + JSON 文件）（blockedBy: 3）

**Goal**: 从 `ANTHROPIC_API_KEY` 和 `~/.agentkit/config.json` 读取配置。

**步骤**:
- `AppConfig` record：apiKey、model、maxTokens
- `ConfigLoader.load()` env 覆盖文件
- 缺 apiKey 时启动期 fail-fast，提示用户

**DoD**: `ConfigLoaderTest` 覆盖 env-only、file-only、env-overrides-file 三场景。

---

#### #15 [S3-TDD] MessageMapper 双向映射 domain ↔ LangChain4j （blockedBy: 4）

**Red**: `MessageMapperTest`
- `mapsUserMessageRoundTrip` — domain → LC4J → domain 等价
- `mapsAiMessageWithToolRequestsRoundTrip`
- `mapsToolResultMessageRoundTrip`

**Green**: 静态方法 `toLc(...)` / `toDomain(...)`，逐类型 switch。

**Refactor**: 用 sealed pattern matching 消除 instanceof 链。

**DoD**: 3 类消息往返等价测试通过。

---

#### #16 [S3-TDD] LangChain4jLlmClient 流式 handler 适配 （blockedBy: 14, 15）

**Red**: `LangChain4jLlmClientTest`（用 LC4J 的 in-memory mock model）
- `forwardsPartialTokensToHandler`
- `assemblesCompleteAiMessageWithToolRequests`
- `propagatesErrorToHandler`

**Green**: 实现 `LlmClient.streamChat`，将 LC4J 的 `StreamingChatResponseHandler` 桥接到 domain 的 `StreamHandler`。

**Refactor**: 抽 `HandlerBridge` 私有内部类。

**DoD**: 3 个测试绿；不依赖真实 API。

---

#### #17 [S3-TDD] Prompt cache breakpoint 标记 （blockedBy: 16）

**Red**: `PromptCacheTest`
- `marksCacheBreakpointAfterSystemPrompt` — 抓取发往 LC4J 的请求，断言系统提示末尾有 cache control
- `marksSecondBreakpointAfterToolDefinitions`

**Green**: 在 `SystemPromptComposer` 输出处和 tool spec 列表末尾插入 ephemeral cache marker（LC4J 的 `AnthropicCacheType.EPHEMERAL`）。

**Refactor**: 提取 `CacheBreakpointStrategy`，便于关闭/调试。

**DoD**: 真实 API 手测 cache_read_input_tokens > 0。

---

#### #18 [S3-Smoke] 真实 Anthropic API 冒烟测试 （blockedBy: 17）

**Goal**: 验证端到端可跑。

**步骤**:
- `EndToEndSmokeIT`（@EnabledIfEnvironmentVariable ANTHROPIC_API_KEY）
- 发送 "say hello"，断言收到非空文本
- CI 跳过；本地 `mvn verify -Psmoke` 手测

**DoD**: 本地手测通过。

---

### S4 内置工具

#### #19 [S4-TDD] BashTool 成功 / 超时 / 非零退出 （blockedBy: 6）

**Red**: `BashToolTest`
- `capturesStdoutOnSuccess` — `echo hi` 返回 "hi"
- `reportsNonZeroExit` — `exit 1` 返回错误结果含 exit code
- `killsOnTimeout` — 5s 超时，sleep 10 被杀
- `propagatesCancellation` — cancel token 触发后立即终止

**Green**: 用 zt-exec 实现 `ProcessRunner`，BashTool 调用之；Windows 用 `cmd.exe`、其他用 `bash -c`。

**Refactor**: 平台判断抽到 `ShellSelector`。

**DoD**: 4 个测试绿；跨平台跑。

---

#### #20 [S4-TDD] FileReadTool 读文件 / 不存在 / 编码 （blockedBy: 6, 25）

**Red**: `FileReadToolTest`
- `readsUtf8Content`
- `returnsErrorWhenFileMissing`
- `truncatesAtMaxLines`（默认 2000 行）
- `recordsReadInFileStateCache`

**Green**: 用 `Files.readString` + 行截断；写入 `FileStateCache`。

**Refactor**: 抽 `FileTextLoader` 处理编码探测。

**DoD**: 4 个测试绿。

---

#### #21 [S4-TDD] FileWriteTool 创建 / 覆盖 （blockedBy: 6, 25）

**Red**: `FileWriteToolTest`
- `createsNewFile`
- `overwritesExistingFile`
- `createsParentDirectoriesIfMissing`
- `requiresReadBeforeOverwrite` — 已存在但未 Read 过时拒绝

**Green**: `Files.writeString` + `FileStateCache` 校验。

**Refactor**: 与 FileEditTool 共享的 `RequireReadGuard` 抽出。

**DoD**: 4 个测试绿。

---

#### #22 [S4-TDD] FileEditTool 唯一匹配 / 多匹配 / 未读拒绝 （blockedBy: 6, 25）

**Red**: `FileEditToolTest`
- `replacesUniqueOccurrence`
- `rejectsWhenOldStringAppearsMultipleTimes`（提示加上下文）
- `rejectsWhenFileNotReadFirst`
- `replaceAllMode` — replace_all=true 时替换全部
- `producesUnifiedDiff` — 返回结果含 diff

**Green**: 字符串查找 + 计数 + 替换；diff 用 `java-diff-utils`（或简易实现）。

**Refactor**: 抽 `StringReplacement` 值对象 + `DiffRenderer`。

**DoD**: 5 个测试绿。

---

#### #23 [S4-TDD] GlobTool 模式匹配 （blockedBy: 6）

**Red**: `GlobToolTest`
- `matchesSimpleStarPattern`
- `matchesDoubleStarRecursive`
- `sortsByModificationTimeDescending`
- `respectsGitignoreByDefault`

**Green**: `FileSystems.getDefault().getPathMatcher("glob:...")` + `Files.walk`；按 `lastModifiedTime` 排序。

**Refactor**: `.gitignore` 解析抽 `GitIgnoreFilter`。

**DoD**: 4 个测试绿。

---

#### #24 [S4-TDD] GrepTool — ripgrep 集成 + Java 回退 （blockedBy: 6）

**Red**: `GrepToolTest`
- `findsMatchesWithRipgrepWhenAvailable`
- `fallsBackToJavaWhenRipgrepMissing`
- `supportsContextLines`（-A/-B/-C）
- `supportsGlobFilter`

**Green**: 启动期探测 `rg --version`；有则走 zt-exec，没则用 Java 正则 + `Files.walk`。

**Refactor**: 抽 `GrepBackend` 接口（Ripgrep/JavaRegex 两实现）。

**DoD**: 4 个测试绿；两后端均覆盖。

---

#### #25 [S4-TDD] FileStateCache 会话级已读记录 （blockedBy: 6）

**Red**: `FileStateCacheTest`
- `recordsReadWithMtime`
- `detectsExternalModificationAfterRead`（mtime 变化）
- `clearedOnSessionEnd`

**Green**: `Map<Path, Instant readAt>`；写入时 `Files.getLastModifiedTime` 校验。

**Refactor**: 线程安全用 `ConcurrentHashMap`。

**DoD**: 3 个测试绿；FileWriteTool/FileEditTool 接入。

---

### S5 权限系统

#### #26 [S5-TDD] PermissionPolicy 四模式决策矩阵 （blockedBy: 6）

**Red**: `DefaultPermissionPolicyTest`
- 矩阵参数化：(mode × tool.readOnly × tool.name) → 期望 Decision
- `defaultModeReadOnlyAlwaysAllow`
- `defaultModeWritePromptsAsk`
- `planModeRejectsWrites`
- `bypassAllowsAll`
- `autoModeAllowsRegisteredSafelist`

**Green**: 实现 `DefaultPermissionPolicy.decide`，模式 + 工具属性双维度判断。

**Refactor**: 用策略对象代替 if/else 链。

**DoD**: 参数化测试覆盖全矩阵。

---

#### #27 [S5-TDD] PermissionService 走 InteractivePrompter （blockedBy: 26）

**Red**: `PermissionServiceTest`
- `allowSkipsPrompter`
- `denySkipsPrompter`
- `askInvokesPrompterAndReturnsDecision`
- `cachesAllowAlwaysDecisionsPerSession`

**Green**: Service 包装 Policy + Prompter；ALLOW_ALWAYS 决策缓存在会话级 `Map<String toolName, Decision>`。

**Refactor**: 缓存抽 `PermissionDecisionCache`。

**DoD**: 4 个测试绿；Prompter 用 Mockito 验证调用次数。

---

#### #28 [S5-TDD] AgentExecutor 调用工具前接入权限拦截 （blockedBy: 13, 19, 27）

**Red**: `AgentExecutorPermissionTest`
- `deniedToolReturnsErrorResultWithoutExecution` — 拒绝时 ToolResult 含 "permission denied" 且工具未被调用
- `allowedToolExecutesNormally`
- `askDecisionRoutesThroughPrompter`

**Green**: 在 `dispatchToolCalls` 内每个 invocation 先调 `PermissionService.check`。

**Refactor**: 拦截链抽 `ToolExecutionPipeline`（permission → execute → record）。

**DoD**: 3 个测试绿；现有 S2 测试不回归。

---

### S6 上下文 + REPL

#### #29 [S6-TDD] ContextProvider 组合与缓存 （blockedBy: 3）

**Red**: `ContextProviderTest`（每 provider 独立 + 组合）
- `AgentsMdProviderReadsFromCwd`
- `AgentsMdProviderMergesParentDirectories`
- `GitStatusProviderReturnsEmptyForNonGitDir`
- `CwdProviderReturnsAbsolutePath`
- `DateProviderReturnsTodayIso`

**Green**: 各实现独立；组合用 `List<ContextProvider>` 顺序拼接。

**Refactor**: 抽 `ContextSection` value type；首次调用结果 memoize。

**DoD**: 5 个 provider 测试绿。

---

#### #30 [S6-TDD] SystemPromptComposer 顺序固化（cache 友好）（blockedBy: 17, 29）

**Red**: `SystemPromptComposerTest`
- `producesStablePrefixAcrossInvocations` — 静态部分（系统指令、CLAUDE.md）字节级稳定
- `appendsDynamicSectionsAfterStablePrefix` — 日期、git status 在变动段
- `placesCacheBreakpointBeforeDynamicSection`

**Green**: 固化 [系统指令 → CLAUDE.md → 工具描述 → cache marker → 动态 context]。

**Refactor**: 用 `PromptSection` 链表 + `render()`。

**DoD**: 字节稳定性测试通过。

---

#### #31 [S6-Infra] JLine REPL 行编辑与历史 （blockedBy: 3）

**Goal**: 可交互的 REPL。

**步骤**:
- `ReplLoop` 用 JLine `LineReader`，启用历史持久化到 `~/.agentkit/history`
- 多行输入（反斜杠续行 或 ``` 围栏）
- 空输入忽略，`exit`/`quit` 终止

**验收**: 手测：方向键回溯历史、Ctrl-A/E 起末行、退出码 0。

---

#### #32 [S6-TDD] SlashCommandParser 识别与分派 （blockedBy: 31）

**Red**: `SlashCommandParserTest`
- `parsesKnownCommand` — `/help` 解析为 HelpCommand
- `treatsNonSlashAsUserMessage` — `hello` 走 LLM
- `parsesArgs` — `/resume abc123` 解析 sessionId
- `unknownSlashReturnsError` — `/foo` 返回 "unknown command"

**Green**: 注册表 `Map<String, SlashCommand>`；命令接口 `execute(args, ctx)`。

**Refactor**: 命令发现用 ServiceLoader 或显式注册。

**DoD**: 4 个测试绿；初始注册 `/help` `/clear`。

---

### S7 中断 + 流式渲染

#### #33 [S7-TDD] CancellationToken 线程安全 （blockedBy: 3）

**Red**: `CancellationTokenTest`
- `initiallyNotCancelled`
- `cancelledIsVisibleAcrossThreads`
- `idempotentCancel`

**Green**: `AtomicBoolean`；`cancel()` + `isCancelled()` + `throwIfCancelled()`。

**Refactor**: 加 `onCancel(Runnable)` 回调列表（CopyOnWriteArrayList）。

**DoD**: 3 个测试绿。

> ⚠️ 此任务被 #13 依赖，应在 S1 完成后尽快做。

---

#### #34 [S7-Infra] SIGINT 二段式处理 （blockedBy: 31, 33）

**Goal**: Ctrl-C 第一次取消当前 turn，第二次退出进程。

**步骤**:
- `Signal.handle("INT", ...)` 注册
- 状态机：IDLE → CANCELLING → EXIT
- CANCELLING 在 turn 结束后回到 IDLE

**验收**: 手测两次 Ctrl-C 路径；turn 结束后单次 Ctrl-C 退出。

---

#### #35 [S7-Infra] OutputRenderer 逐 token 输出 （blockedBy: 31）

**Goal**: 用户看到 token 实时流出。

**步骤**:
- `OutputRenderer.onPartialText(token)` → `System.out.print(token); System.out.flush()`
- 工具调用提示用单独颜色（jansi 或 JLine ANSI）
- 错误用红色到 stderr

**验收**: 手测流式可见，无缓冲卡顿；非 TTY（管道）禁用颜色。

---

### S8 持久化

#### #36 [S8-TDD] FileChatMemoryStore JSONL 读写 （blockedBy: 4）

**Red**: `FileChatMemoryStoreTest`
- `savesAndLoadsRoundTrip` — 任意消息序列写入后读取等价
- `appendIsAtomic` — 模拟中途崩溃，已写消息可读
- `deleteRemovesFile`
- `preservesToolUseAndToolResultPairing`

**Green**: Jackson 序列化每行；append 模式 + fsync。

**Refactor**: 抽 `JsonlAppender` 工具类。

**DoD**: 4 个测试绿；用 `Files.move` 保证原子性。

---

#### #37 [S8-TDD] /resume 命令恢复会话 （blockedBy: 32, 36）

**Red**: `ResumeCommandTest`
- `restoresMessageHistoryFromStore`
- `doesNotReExecuteTools` — 历史中的 tool_use 不重新调用
- `failsGracefullyOnMissingSession`

**Green**: SlashCommand `/resume <sessionId>` 调 store → 重建 Conversation。

**Refactor**: 与 ConversationOrchestrator 复用 conversation 加载路径。

**DoD**: 3 个测试绿；恢复后可继续对话。

---

#### #38 [S8-Infra] 会话 ID 生成与默认持久化路径 （blockedBy: 14）

**Goal**: 每次启动生成 SessionId 并选择存储路径。

**步骤**:
- `SessionId.fresh()` 用 UUIDv7（时间有序）
- 默认路径 `~/.agentkit/sessions/<id>.jsonl`
- 可配置 override

**验收**: `SessionIdTest` 时序唯一性；路径解析跨平台正确。

---

### MVP-Gate

#### #39 [MVP-Gate] 端到端冒烟与发布候选 （blockedBy: 18, 20, 21, 22, 23, 24, 28, 30, 34, 35, 37, 38）

**Goal**: 验证 MVP 全链路可用。

**手测脚本**:
1. 启动 → 输入 "list files here"，模型调用 GlobTool 并复述
2. "read README.md and summarize" → FileReadTool → 摘要文本
3. "edit foo.txt: replace bar with baz" → FileEditTool（应触发 ASK）
4. Ctrl-C 一次取消，二次退出
5. 重启后 `/resume <id>` 看到历史

**DoD**: 5 条全部通过；记录 cache hit rate / cost。打 tag `v0.1.0-mvp`。

---

## S9 Agent Runtime Hardening

> 来源：2026-07-29 Claude Code / Codex agent 流程对照审计。完整证据、领域模型、依赖图和非目标见
> [`docs/agentkit-kernel-high-priority-tasks.md`](docs/agentkit-kernel-high-priority-tasks.md)。
> 所有任务完成后再更新 `DESIGN.md §16` 的正式决策。

### GATE-0 [Test] 恢复跨平台全量构建

**状态**：已完成（2026-07-29）。

**Goal**：修复 Linux/Windows 路径分隔符测试，使 `mvn -B -ntp clean verify` 成为可信基线。

**DoD**：kernel 与 CLI 的 user-home 路径测试使用平台真实 `Path` 语义；全量 verify 通过。

---

### #40 [S9-Kernel-TDD] AgentRunContext 单一作用域与 AgentExecutor 可重入 （blockedBy: GATE-0）

**状态**：已完成（2026-07-29）。

**Red**：provided cwd/cancellation 到达工具；并发 run 不共享 cwd、取消、预算、权限缓存和 file-state 授权。

**Green**：引入 `RunId`、`WorkspaceId`、`AgentRunContext`；dispatcher 改为 run scope；移除 kernel 内隐式 `user.dir`/allow-all 组合。

**Refactor**：静态 runtime 配置与动态 run state 分离；StructuredAgent/SubAgent/agent 包统一透传。

**DoD**：单 executor 并发运行两个 workspace 稳定；所有外部动作关联同一 RunId；ArchUnit 全绿。

---

### #41 [S9-Kernel-TDD] AssistantTurn / tool batch 完整 settle （blockedBy: 40）

**状态**：已完成（2026-07-29）。

**Red**：重复 ToolUseId、pending batch 后追加消息、budget/unknown-tool/invalid-args/permission/listener/cancel 异常路径。

**Green**：`ToolResultStatus` 保留到 conversation/provider；每批输出等长、有序、全部 terminal 的 outcome。

**Refactor**：完整 settle 不变量归 domain，dispatcher 只执行策略；session codec 向后兼容。

**DoD**：所有可预期失败均生成 result，不产生孤儿 tool-use；并发异常测试稳定。

---

### #42 [S9-Kernel-TDD] AgentRunResult 与原生 terminal-tool 退出 （blockedBy: 41）

**状态**：已完成（2026-07-29）。

**Red**：terminal 成功后无第二次 LLM；失败校验可纠正；mixed/multiple terminal 拒绝；stop reason/usage/payload 可读。

**Green**：executor 返回 `AgentRunResult`；成功 terminal 在配对完成后立即停止；完整验证支持的 JSON Schema。

**Refactor**：diagnosis `RunSummary` 建立 adapter；移除 StructuredAgent sink/第二轮时序依赖。

**DoD**：所有预期终态无需解析异常文本；provider、diagnosis、coding 全回归。

---

### #43 [S9-Kernel-TDD] WorkspaceBoundary、参数级权限与安全默认值 （blockedBy: 40）

**状态**：已完成（2026-07-29）。

**Red**：`..`、绝对路径、symlink 越界；permission cache 跨 scope/参数泄漏；deny 优先；默认不 BYPASS。

**Green**：文件工具必经 `WorkspaceBoundary`；permission rule 绑定 scope + 参数；引入 `SecretProvider` port 和安全默认值。

**Refactor**：平台路径 resolver 与 policy 分离；记录 Bash 在 L0 不是宿主 sandbox 的残余风险。

**DoD**：kernel 文件工具不能越 workspace；permission/secret 审计带 scope 且不泄密；跨平台测试通过。

---

### #44 [S9-Kernel-TDD] 可取消 LlmCall、deadline 与输出预算 （blockedBy: 40, 42）

**状态**：已完成（2026-07-29）。

**Red**：首 delta 前取消、timeout 主动取消 provider、late callback 隔离、唯一终态、child 不能放大 parent 限制。

**Green**：`LlmClient` 返回可取消 handle/completion；run context 提供 deadline/limits；tool/child 继承限制。

**Refactor**：区分 provider timeout、run deadline、tool timeout；stream/final output 只计量一次。

**DoD**：无输出挂起调用可取消；timeout 后无二次完成或资源泄漏；无 runtime 魔法等待常量。

---

### #45 [S9-Kernel-TDD] ContextPolicy 与全局 ToolOutputPolicy （blockedBy: 41, 42, 44）

**状态**：已完成（2026-07-29）。

**Red**：每轮 compact、overflow 单次恢复、batch-safe compaction、summary 失败不丢历史、所有工具输出统一受限。

**Green**：executor 每轮委托 `ContextPolicy`；显式 compaction boundary；dispatch 必经 `ToolOutputPolicy`。

**Refactor**：token estimator/window/retry 策略组合；稳定输出元数据协议与 artifact 持久实现解耦。

**DoD**：所有 agent 入口共享策略；compact 前后配对成立；大结果有完整/截断/明确 omission 状态。

---

### #46 [S9-Kernel-TDD] append-only RunEventStore 与安全 resume （blockedBy: 41, 42, 45）

**状态**：已完成（2026-07-29）。

**Red**：append-only、event projection、settled 不重放、in-flight 标 UNKNOWN、terminal/usage/compaction 可恢复、尾记录容错。

**Green**：定义版本化 `RunEvent`/`RunEventStore` port 与 append-only 文件实现；Conversation/RunResult 从事件重建；started-but-unsettled 恢复为 UNKNOWN，且恢复路径不持有工具执行能力。

**Refactor**：事实存储与 listener 分离；listener 失败不影响 run/event；落盘敏感字段递归脱敏且文本有界；schema v1/未来版本/必要字段/尾截断兼容测试齐全；旧 ChatMemoryStore/session 保持可读兼容路径。

**DoD**：kill/restart 不重复已完成工具；终态可完整恢复；不猜测外部副作用；全量 verify 通过。

---

## S10 Agent Runtime Extensions

> 来源：2026-07-29 Claude Code / Codex 后续能力对照审计。详细证据、测试矩阵、边界和非目标见
> [`docs/agentkit-kernel-follow-up-tasks.md`](docs/agentkit-kernel-follow-up-tasks.md)。
> 已确认交付顺序：`#47 → #48 → #49 → #50/#51 → #52/#53 → #54/#55`。
> `#56` 事件索引、retention 与多 writer fencing 仍为条件触发候选，不属于本阶段。

### #47 [S10-Kernel-TDD] AgentSpec + SubAgentRuntime / SubAgentHandle （blockedBy: 40, 42, 43, 44, 46）

**状态**：已完成（2026-07-29）。

**Red**：child capability 必须是 parent 子集；child budget 计入 parent；deadline 不得放大；depth/concurrency 受硬限制；follow-up 复用 child conversation；parent cancel 传播到 child LLM/工具；terminal payload 穿过 child boundary。

**Green**：引入领域无关 `AgentSpec`、`AgentId`、`ToolCapabilitySet`、`ModelTier`、`SubAgentRuntime`、`SubAgentHandle` 与显式 child execution scope；每次 child run 使用独立 RunId/CancellationToken，共享分层预算账本与 workspace boundary。

**Refactor**：`SubAgentTool` 退化为 runtime adapter；`StructuredAgent` 及 diagnosis/coding 角色只提供 spec 与 payload→VO 映射；javadoc 领域中立。

**DoD**：能力、预算、deadline、depth/concurrency、取消方向和 conversation 归属由 runtime 强制；child handle 可 follow-up/cancel 并返回完整 `AgentRunResult`；不实现 peer 黑板、自动拆分或分布式调度。

---

### #48 [S10-Kernel-TDD] typed AgentInterceptor 生命周期扩展 （blockedBy: 41, 42, 46）

**状态**：已完成（2026-07-29）。

**Red**：pre-tool deny 仍 settle；observer failure 隔离；blocking failure 有明确 stop reason；声明顺序稳定；terminal validation 不破坏配对；sub-agent lifecycle 携带 parent/child RunId。

**Green**：提供 typed pre/post lifecycle SPI 与 `Continue`、`Deny`、`ReplaceContext` 等显式 decision；接入 LLM、tool、compaction、run stop 和 sub-agent 边界。

**Refactor**：固定 interceptor 顺序和失败分类；`PermissionPolicy` 继续作为独立安全决策；不允许 exception 充当正常控制流。

**DoD**：宿主可组合审计、脱敏和策略扩展；tool-use 一旦进入 conversation，无论 interceptor 结果都完整 settle；不加入任意 shell/script hook。

---

### #49 [S10-Kernel-Infra-TDD] MCP client、工具适配与生命周期 （blockedBy: 43, 44, 45, 46, 48）

**状态**：已完成（2026-07-29）。

**Red**：fake stdio discover/call/close；HTTP timeout/cancel/reconnect；namespace 冲突；malformed schema/result settle；destructive tool 走 ASK/DENY；secret 不泄漏；catalog refresh 原子；大 catalog 延迟暴露。

**Green**：实现 `McpToolAdapter`、`McpServerManager`、stdio/HTTP transport、`<server>.<tool>` 命名空间、认证、timeout/cancel 和动态 catalog。

**Refactor**：MCP annotation 只作为 permission 输入，不能绕过本地 deny；统一走 tool batch、output policy 和 run event；关闭 server 时回收进程/线程。

**DoD**：至少一个 fake stdio 集成测试和一个 HTTP 合同测试；不依赖真实 MCP 服务；不在 kernel 硬编码业务 server。

---

### #50 [S10-Kernel-TDD] Background TaskHandle + output artifact store （blockedBy: 41, 43, 44, 45, 46, 47）

**状态**：待开始。

**Red**：后台启动立即返回 handle；游标增量读不重复；workspace 间不可读/停；cancel 终止进程树；原 invocation 只 settle 一次；大输出保存 artifact；过期 artifact 不越界。

**Green**：引入 `TaskId`、`TaskHandle`、task lifecycle、增量输出 cursor 和有 scope/size/TTL 的 artifact store；后台启动立即 settle 为 TaskId，status/read/stop 使用新工具调用。

**Refactor**：统一 process tree 回收、run stop 清理、preview/reference 与脱敏策略；不让原始 tool-use 跨用户回合 pending。

**DoD**：长 Bash/外部工具可后台运行、监控和停止；完整输出可受控引用；不实现分布式队列或跨机器 worker。

---

### #51 [S10-Kernel-TDD] WAITING_FOR_INPUT / WAITING_FOR_APPROVAL 可恢复运行态 （blockedBy: 42, 43, 46）

**状态**：待开始。

**Red**：ASK 在执行工具前暂停；批准后原 invocation 恰好执行一次；拒绝后 settle 为 denied；resume token 单次且不可跨 workspace；答案追加为新事件。

**Green**：定义通用 `RunSuspension`、input/approval envelope 与 resume token；持久化 pending request 后返回 waiting，新 run segment 恢复同一 session。

**Refactor**：CLI/Web 共用异步 suspension contract；coding/diagnosis 的计划与审批业务规则仍留各自领域包。

**DoD**：application thread 不阻塞等待 prompt；旧事件不被改写；token 绑定 session/workspace/request 并单次消费。

---

### #52 [S10-Kernel-TDD] 高级 session fork / checkpoint / rewind （blockedBy: 43, 46；涉及后台进程时再 blockedBy: 50）

**状态**：待开始。

**Red**：fork 引用不可变 parent sequence；rewind 新建 branch 不删历史；kernel 文件 checkpoint 可恢复；报告不可逆副作用；branch 不可跨 workspace。

**Green**：引入 `SessionBranchId`、fork metadata、append-only branch event 与 `FileCheckpointProvider`；文件外副作用显式标记 non-reversible。

**Refactor**：区分 conversation 恢复、kernel 文件恢复和残余外部副作用；worktree 建立/合并仍属于 coding/platform。

**DoD**：历史可审计且不物理重写；不承诺 Bash/MCP/数据库的通用事务回滚。

---

### #53 [S10-Kernel-TDD] provider-neutral ModelPolicy / RetryPolicy （blockedBy: 42, 44, 46；与 47 协同）

**状态**：待开始。

**Red**：assistant turn 被接受前可重试瞬态失败；认证错误不重试；retry 计入预算/deadline；不重放 settled tool；记录实际 fallback model。

**Green**：定义 provider-neutral model/retry policy、有限次数退避、可测试时钟和可选 fallback；`ModelTier` 通过 selector 解析实际 client/model。

**Refactor**：context overflow 继续由 context policy 专管；provider adapter 校验 prompt/tool schema 兼容性；默认关闭 fallback。

**DoD**：重试只包围尚未形成完整 assistant turn 的 LLM call；工具副作用绝不因 provider retry 重放。

---

### #54 [S10-Platform-Infra-TDD] AgentManifest + coding 正式入口 （blockedBy: 42, 47）

**状态**：待开始。

**Red**：duplicate agent id 启动失败；缺 required config 在运行前失败；manifest capability 与实际 AgentSpec/tool boundary 一致；模块依赖单向。

**Green**：agent 模块提供显式 `AgentManifest`；平台 registry 负责发现/派发；coding 增加稳定 `CodingEngine`/builder/entry point。

**Refactor**：组合根不再硬编码具体 agent 拼装；ArchUnit 保证 kernel 不依赖 agent 包且 agent 包互不依赖。

**DoD**：diagnosis/coding 可由同一宿主入口派发；不引入反射 classpath scanning、Spring/Guice 或插件子系统。

---

### #55 [S10-CLI-Platform-TDD] 组合根、slash command 与 SIGINT 接线清理 （blockedBy: 40, 46, 54）

**状态**：待开始。

**Red**：help 只列真实注册命令；`/clear` 重置 active conversation；`/resume` 走 event recovery；第一次 SIGINT 只取消 active run且不污染下一轮；agent 选择/配置错误可见。

**Green**：slash command 声明与注册同源；CLI 用例端口实现 clear/resume；每次 run 新建 context/token；通过 manifest registry 选择 agent。

**Refactor**：SIGINT 退出策略留 CLI；UI 逻辑不下沉 kernel；补齐 clear/resume/SIGINT 端到端测试。

**DoD**：help 中命令全部可用；unknown in-flight invocation 恢复时明确展示；不增加富终端 UI、IDE bridge 或插件系统。

---

## 推荐执行顺序

> 沿关键路径推进，最大化解锁度。可使用 `/loop` 或 task-executor 自动驱动。

### Week 1（MVP 前半段）

| 日 | 任务 |
|---|---|
| Day 1 上午 | #1 → #2 → #3（S0 完成） |
| Day 1 下午 | #4 → #6 → #7 → #5 → #8（S1 完成） |
| Day 2 上午 | #33（提前做，解锁 #13）→ #9 → #10 |
| Day 2 下午 | #11 → #12 → #13（S2 完成） |
| Day 3 上午 | #14 → #15 → #16 |
| Day 3 下午 | #17 → #18（S3 完成，真实 API 跑通） |
| Day 4 | #25 → #19 / #20 / #21 / #22 / #23 / #24（S4 完成） |

### Week 2（MVP 后半段）

| 日 | 任务 |
|---|---|
| Day 5 | #26 → #27 → #28（S5 完成） |
| Day 6 上午 | #29 → #30；#31（S6 前半） |
| Day 6 下午 | #32（S6 完成） |
| Day 7 上午 | #34 → #35（S7 完成） |
| Day 7 下午 | #36 → #38 → #37（S8 完成） |
| Day 8 | #39 MVP 验收 + tag |

---

## 注意事项

1. **每个 TDD 任务必须严格 Red → Green → Refactor**：先写失败测试 → 提交红；再写最小实现 → 提交绿；最后清理 → 提交重构。三次提交，不许跳步。
2. **每个 Green 阶段不超 50 行实现**：超过即拆分。
3. **Refactor 阶段必须保持测试绿**：任何回归立即回滚到上一个绿点。
4. **Infra 任务也要有验收测试**：尽量自动化；纯手测必须留下手测记录截图或终端输出。
5. **每完成一个任务**：用 `TaskUpdate` 把 status 置为 `completed`，并 push 到 git。
