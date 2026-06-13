# 诊断引擎化实施方案（Diagnose Engine Plan）

> 把本项目从「claude-code CLI 复刻」转向「agent-web 的进程内诊断 Agent 引擎」。
>
> @author zhourui(V33215020)
> @since 2026-06-08

---

## 0. 背景与定位转变

本项目（`claude-code-langchain4j`）原目标是复刻 claude-code 的 CLI 主循环。现重新定位为 **agent-web 的进程内 agent 引擎**。

`agent-web`（`D:\ai_worspace\agent-web`，Spring Boot 3.3.13 / JDK 21）是最终使用者，它已经具备 Web UI、SSE、会话持久化（SQLite 三级）、鉴权、诊断编排、RAG 召回、issue-log 等完整能力。它现在驱动 AI 的方式是 **spawn 外部 CLI 子进程**（`claude` / `codex` / `cursor`），经 `CliDialect` 策略路由、`AgentGateway` 端口统一。

本引擎的价值是 **取代外部 CLI 子进程**：

- 干掉外部 CLI 依赖（安装 / 登录 / 版本漂移）与子进程冷启动（Cursor 为此专门做了 ACP 常驻进程，进程内无此问题）。
- 诊断工具集完全可控——这是诊断 Agent 的能力上限所在，外部 CLI 的工具集改不动。

### 已确认决策（用户拍板）

| 议题 | 决策 | 影响 |
|---|---|---|
| 集成形态 | **进程内 jar 依赖** | 本引擎打成 jar，agent-web 加 `AgentType.NATIVE` + 一个 in-process `AgentGateway` 实现直接调引擎。本项目**不引入任何 web 框架**（之前 "Spring Boot WebFlux" 作废）。两者同 JVM（均 JDK 21）。 |
| 工具边界 | **纯只读诊断** | 工具集只读，权限 `BYPASS` 只读 + `DENY` 一切写，无需异步审批。 |
| 会话状态 | **引擎无状态** | 会话归 agent-web 的 `SessionRepository`（single source of truth）。引擎每次按传入历史重建 `Conversation`，旁路 `FileChatMemoryStore`。 |

---

## 1. 目标与边界

### 1.1 必须实现（MVP）

- 一个纯 Java 签名的引擎门面 `DiagnoseEngine`，被 agent-web 进程内调用。
- 把引擎事件序列化为 **Claude CLI `stream-json` 兼容格式**，让 agent-web 前端 / SSE / 落库零改动复用。
- 引擎无状态：按传入历史重建 `Conversation`。
- 只读权限硬约束。
- agent-web 侧接入 `AgentType.NATIVE`，端到端跑通一条诊断对话。

### 1.2 计划实现（MVP 之后）

- 只读诊断工具集：日志查询 / ES / MySQL / Redis / HTTP-GET / Dubbo（对应用户现有 skill 能力）。
- 大工具结果截断 + 上下文自动压缩（auto-compact）。
- 子 Agent（并行验证多个排查假设）。
- token / cost 透出给 agent-web 落库。

### 1.3 不在范围

- 本项目自带 web / SSE / 会话持久化 / 鉴权（agent-web 全有）。
- MCP 客户端、JSON 单次模式（用户已确认不要）。
- `FileWrite` / `FileEdit`（诊断只读，修复建议走文本输出）。
- `interfaces/cli` 作为主交付（降级为独立调试入口，保留不删）。

---

## 2. 集成架构

### 2.1 两侧职责切分

```
┌─────────────────────── agent-web (Spring Boot, 不改前端) ──────────────────────┐
│  ChatController / DiagnoseController  →  ChatAppService / DiagnoseAppService     │
│        │                                                                         │
│        ▼  AgentGateway.runStream(NATIVE, workingDir, msg, sessionId, ...,        │
│                                   onChunk, onExit)                               │
│  NativeAgentGateway implements AgentGateway   ← 新增（agent-web repo, 改动很小） │
│        │  委托                                                                    │
└────────┼─────────────────────────────────────────────────────────────────────┘
         │  进程内调用（同 JVM，纯 Java 签名）
         ▼
┌─────────────────────── claude-code-langchain4j (引擎 jar) ─────────────────────┐
│  DiagnoseEngine.runStream(workingDir, msg, sessionId, history,                  │
│                           onChunk, onExit, cancel)                              │
│        │  1. 按 history + msg 重建 Conversation（无状态）                         │
│        │  2. AgentExecutor.run(conversation, cancel, listener)                  │
│        │  3. listener = ClaudeStreamJsonListener（事件 → stream-json 行）         │
│        ▼                                                                         │
│  AgentExecutor（不改） → LangChain4jLlmClient → Anthropic API                    │
│                       → ToolRegistry（只读诊断工具）                              │
└────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 事件契约：Claude `stream-json`

agent-web 的统一前端契约就是 **Claude CLI `--output-format stream-json --include-partial-messages` 的逐行 JSON**。Codex / Cursor 靠 normalizer 翻译过去，Claude 是直通（pass-through）。

因此 `NativeAgentGateway` 的 `normalizeChunk` 也是 **pass-through**——引擎直接产出统一契约。需要对齐的事件（最终以 D0 采集的真实样本为准）：

> 逐字段权威契约见 [`docs/samples/README.md`](../samples/README.md)（基于 agent-web 消费侧实测）。下表为骨架速览。

| 时机 | 事件骨架 | 来源钩子 |
|---|---|---|
| 会话开始 | `{"type":"system","subtype":"init","session_id":"...","cwd":"..."}` | 门面进入时 |
| 文本增量 | `{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}}` | `onAssistantTextDelta` |
| 工具调用开始 | `{"type":"stream_event","event":{"type":"content_block_start","content_block":{"type":"tool_use",...}}}` + `input_json_delta` + `content_block_stop` | `onToolUseStart` |
| 工具结果 | `{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"...","content":"..."}]}}` | `onToolUseEnd` |
| 本轮结束 | `{"type":"result","subtype":"success","result":"...","session_id":"...","usage":{...}}` | `onTurnComplete` |

**实测修正**：文本/工具增量必须包在 `{"type":"stream_event","event":{...}}` 外壳里——前端只认顶层 `type=="stream_event"` 再读 `event.*`，裸 `content_block_delta` 会被丢弃。

`extractResumeId(NATIVE, line)` 解**顶层** `session_id`（init 行与 result 行都带）；`isTurnEnd(NATIVE, line)` 认 `type=="result"`。

### 2.3 关键复用点

`application.AgentEventListener` 已有全套钩子（`onLlmRequestStart` / `onAssistantTextDelta` / `onToolUseStart` / `onToolUseEnd` / `onTurnComplete` / `onError`），`OutputRenderer` 是它的 CLI 实现。**接 agent-web 只需新增一个 `ClaudeStreamJsonListener implements AgentEventListener`，`AgentExecutor` 一行不改。**

---

## 3. 关键设计决策

### D-1 引擎无状态，会话由 agent-web 持有

`DiagnoseEngine.runStream` 接收 `List<TurnMessage> history`，每次重建 `Conversation` 后 append 新 `userMessage`。引擎不写任何会话文件。`resumeId` 即 agent-web 的 `sessionId`，引擎仅在 `system.init` 事件回显，不据此加载状态。

**理由**：避免两套持久化与一致性问题；agent-web 的 `SessionRepository` 已是 single source of truth。

### D-2 门面签名不泄漏 langchain4j / 内部 domain 类型

`DiagnoseEngine` 的入参出参只用 JDK 类型 + 引擎自有简单 DTO（`TurnMessage`、`CancellationHandle`）。agent-web 依赖 jar 时接口干净，传递依赖最小。

```java
public interface DiagnoseEngine {
    void runStream(RunRequest request,
                   Consumer<String> onChunk,   // 每行 = Claude stream-json
                   IntConsumer onExit,          // 退出码，-1=超时/取消
                   CancellationHandle cancel);
    void stop(String sessionId);
    boolean isRunning(String sessionId);
}
// RunRequest: workingDir, userMessage, sessionId, env, timeoutSeconds, List<TurnMessage> history
// TurnMessage: role(USER/ASSISTANT/TOOL_RESULT) + text + 可选 toolUse/toolResult 字段
```

### D-3 分层归属

| 新增物 | 包 | 层 | 依赖方向校验 |
|---|---|---|---|
| `DiagnoseEngine` 接口 + `DefaultDiagnoseEngine` | `interfaces/engine` | driving adapter（与 `interfaces/cli` 平行） | interfaces → application ✓ |
| `ClaudeStreamJsonListener`（事件→行） | `interfaces/engine` | driving adapter | 实现 application 的 `AgentEventListener` ✓ |
| `ClaudeStreamJsonWriter`（纯 JSON 拼装） | `interfaces/engine`（或 `infrastructure/llm` 复用 jackson） | —— | 纯函数，可单测 |
| `RunRequest` / `TurnMessage` / `CancellationHandle` | `interfaces/engine` | DTO | —— |
| 诊断工具（`LogQueryTool` 等） | `infrastructure/tools` | infrastructure | 实现 domain `Tool` ✓ |
| `ReadOnlyPermissionPolicy` | `infrastructure/permission` | infrastructure | 实现 domain `PermissionPolicy` ✓ |

`DefaultDiagnoseEngine` 编排 `AgentExecutor`、重建 `Conversation`、管 stop registry——属用例编排，故落 `interfaces/engine` 调 `application`。最终以 `LayeredArchitectureTest` 通过为准，实现时可微调包名。

### D-4 stop / 中断 / 超时

`DefaultDiagnoseEngine` 维护 `Map<String sessionId, CancellationToken>`。`stop(sessionId)` 触发对应 token；`timeoutSeconds` 到点也触发并以 `onExit(-1)` 收尾。与 agent-web 的 `stopStream(sessionId)` 桥接。注意 agent-web 在 fork 线程跑 `runStream`，引擎虚拟线程工具调度需正确响应中断。

---

## 4. 阶段与任务分解

> 风格对齐 `TASKLIST.md`：TDD 任务走 Red → Green → Refactor 三次提交；函数 ≤50 行、嵌套 ≤3 层。`blockedBy` 标依赖。

### 阶段 E0：基准与准备

#### #E0-1 [Infra] 采集 Claude stream-json 真实基准

**Goal**：拿到逐字段对齐依据，避免凭空造事件。

**步骤**：
- 用 `claude --print --output-format stream-json --verbose --include-partial-messages` 跑一条「纯文本 + 一次工具调用」对话，存样本到 `docs/samples/claude-stream-json-*.ndjson`。
- 对照 agent-web 前端 `static/js/app.js` 的 `parseStreamJson` 与 `static/js/lib/formatters.js`，记录前端实际消费的事件类型与字段到 `docs/samples/README.md`。

**DoD**：样本 + 字段映射表落盘。

---

### 阶段 E1：事件序列化（MVP 核心）

#### #E1-1 [TDD] ClaudeStreamJsonWriter 纯 JSON 拼装 （blockedBy: E0-1）

**Red**：`ClaudeStreamJsonWriterTest`
- `writesSystemInit` — 含 `type/subtype/session_id/cwd`
- `writesTextDelta` — `content_block_delta` + `text_delta` 转义正确
- `writesToolUseStartAndInputDelta` — `content_block_start(tool_use)` + `input_json_delta`
- `writesToolResult` — `user.message.content[].tool_result` 关联 `tool_use_id`
- `writesResult` — `type=result` 含 `session_id` 与可选 `usage`

**Green**：用 jackson 逐方法拼 JSON 行（每方法返回一行字符串）。

**Refactor**：抽 `event(type, fields)` 私有构造器去重。

**DoD**：5 测试绿；输出与 E0 样本字段一致。

#### #E1-2 [TDD] ClaudeStreamJsonListener 事件桥接 （blockedBy: E1-1）

**Red**：`ClaudeStreamJsonListenerTest`（用假的 `Consumer<String>` 收集行）
- `emitsSystemInitOnce` — 首次产出 init，且仅一次
- `streamsTextDeltas` — 多次 `onAssistantTextDelta` → 多行 delta
- `emitsToolUseThenResult` — `onToolUseStart` → tool_use 行；`onToolUseEnd` → tool_result 行
- `emitsResultOnTurnComplete`
- `emitsErrorAsResultFailure` — `onError` → `result.subtype=error_*`

**Green**：`implements AgentEventListener`，每钩子调 writer + `onChunk.accept(line)`。

**Refactor**：session_id / 首行 init 用状态位守卫，保证幂等。

**DoD**：5 测试绿。

---

### 阶段 E2：引擎门面 + 无状态会话（MVP 核心）

#### #E2-1 [TDD] TurnMessage / RunRequest DTO 与 Conversation 重建 （blockedBy: 无）

**Red**：`ConversationRebuilderTest`
- `rebuildsUserAssistantHistoryInOrder`
- `rebuildsToolUseAndToolResultPairing` — 历史中的 tool_use / tool_result 配对还原，且通过 `ToolUseInvariantChecker`
- `appendsCurrentUserMessageLast`

**Green**：`ConversationRebuilder.from(history, userMessage)` → 新 `Conversation`，逐条 append。

**Refactor**：`TurnMessage` 用 sealed/record 表达三种角色，pattern matching 消 instanceof。

**DoD**：3 测试绿；配对不变式不破。

#### #E2-2 [TDD] DefaultDiagnoseEngine 编排 + stop registry （blockedBy: E1-2, E2-1）

**Red**：`DefaultDiagnoseEngineTest`（注入 `StubLlmClient` + `FakeTool`）
- `streamsTextThenExitsZero` — 纯文本对话产出行序列 + `onExit(0)`
- `runsToolAndStreamsToolResult` — 工具被调一次，产出 tool_use + tool_result 行
- `stopCancelsRunningSession` — `stop(sessionId)` 后 `onExit(-1)`，LLM 调用 ≤1 次
- `isRunningReflectsLifecycle`

**Green**：重建 Conversation → 注册 `CancellationToken` → `AgentExecutor.run(conv, cancel, ClaudeStreamJsonListener)` → 完成/异常映射 `onExit`。

**Refactor**：registry 抽 `RunningSessions`；超时调度抽私有方法保 `runStream` ≤50 行。

**DoD**：4 测试绿；复用 `testsupport.StubLlmClient` / `FakeTool`，不引新 mock。

---

### 阶段 E3：只读权限硬约束（MVP 核心）

#### #E3-1 [TDD] ReadOnlyPermissionPolicy 拒绝一切写工具 （blockedBy: 无）

**Red**：`ReadOnlyPermissionPolicyTest`
- `allowsReadOnlyTool` — `tool.isReadOnly()==true` → `ALLOW`
- `deniesWriteTool` — `isReadOnly()==false` → `DENY`（不走 ASK）
- `deniesBashByDefault` — Bash 默认非只读 → `DENY`（除非显式白名单只读命令，MVP 直接 DENY）

**Green**：`decide` 仅看 `tool.isReadOnly()`，写即 `DENY`。

**Refactor**：与 `DefaultPermissionPolicy` 共享判定的部分抽出。

**DoD**：3 测试绿；`DefaultDiagnoseEngine` 默认装配此 policy。

---

### 阶段 E4：agent-web 接入（MVP 收尾，改动在 agent-web repo）

#### #E4-1 [Infra] AgentType.NATIVE + NativeAgentGateway （blockedBy: E2-2, E3-1）

**Goal**：agent-web 用进程内引擎跑通诊断对话。

**步骤**（agent-web repo）：
- `AgentType` 增 `NATIVE`。
- 引入引擎 jar，校验 `mvn dependency:tree` 无冲突（jackson / slf4j / netty 与 Spring Boot 3.3 BOM）。
- 新增 `NativeAgentGateway implements AgentGateway`：`runStream` 委托 `DiagnoseEngine`，桥接 `onChunk` / `onExit` / `stopStream` / `isRunning`；`normalizeChunk` 直通；`extractResumeId` 解 init.session_id；`isTurnEnd` 认 result。
- `application.yml` 加 `agent.cli.native` 配置位（apiKey / model 走环境变量）。

**验收**：`@SpringBootTest` 用 `StubLlmClient` 跑 chat / diagnose 各一条，SSE 事件被前端 `parseStreamJson` 正常解析（沿用 `DiagnoseFlowTest` 模式）。

#### #E4-MVP-Gate [Gate] 端到端跑通 （blockedBy: E4-1）

**手测脚本**：
1. agent-web 选 `NATIVE`，发「say hello」→ 浏览器看到流式文本。
2. 发一条触发工具的诊断问题 → 诊断历史页正确展开工具调用。
3. 运行中点「停止」→ 进程内引擎中断，前端收尾。

**DoD**：3 条通过；记录 cache hit / 首字延迟，对比外部 CLI 路径。

---

### 阶段 E5：诊断工具集

> 每个工具一个 TDD 任务；测试用 stub，**不连真实外部系统**（对齐项目 TDD 纪律）。连接配置走环境变量 / `application.yml`，对应用户现有 skill 能力。所有工具 `isReadOnly()==true`。

| 任务 | 工具 | 对应 skill | 后端 |
|---|---|---|---|
| #E5-1 | `LogQueryTool`（按 traceId / 关键词查日志） | oppo-log-query | OPPO 云日志 API |
| #E5-2 | `EsReadTool`（search / count / get / mapping） | es-reader | Elasticsearch 只读 |
| #E5-3 | `MysqlReadTool`（DESCRIBE / SELECT / 行数） | mysql-reader | MySQL 只读连接 |
| #E5-4 | `RedisReadTool`（scan / get / ttl / 类型） | redis-reader | Redis 只读 |
| #E5-5 | `HttpGetTool`（只读接口验证） | http-invoke | HTTP GET |
| #E5-6 | `DubboInvokeTool`（telnet invoke，只读接口） | dubbo-invoke | Dubbo Provider |

每个任务的 Red/Green/Refactor：
- **Red**：成功路径 + 参数校验失败 + 后端错误（stub 模拟），≥3 测试。
- **Green**：实现 `Tool`，连接细节封装在后端 client，注入便于 stub。
- **Refactor**：抽公共结果格式化 / 大结果截断接口（见 E6-1）。
- **DoD**：≥3 测试绿；`isReadOnly()==true`；注册进 `ToolRegistry`。

---

### 阶段 E6：上下文与大结果治理

#### #E6-1 [TDD] 工具结果截断策略 （blockedBy: E5-*）

**Red**：`ToolResultTruncatorTest`
- `keepsSmallResultIntact`
- `truncatesLargeResultWithMarker` — 超阈值保留头尾 + `... +N lines / N tokens omitted`
- `truncatesByTokenEstimate` — 用 `TokenEstimator` 估算

**Green**：诊断结果（日志 / ES）入会话前过截断器。

**DoD**：3 测试绿；阈值可配。

#### #E6-2 [TDD] 上下文自动压缩 auto-compact （blockedBy: E6-1）

**Red**：`ContextCompactionServiceTest`
- `noCompactBelowThreshold`
- `compactsAboveThreshold` — 超 85% 触发 LLM 摘要并装边界
- `preservesRecentMessagesAfterBoundary`

**Green**：实现 `ContextCompactionService`（DESIGN §6.3）+ `CompactionBoundary`（域内补齐）；`DefaultDiagnoseEngine` 每轮前调 `maybeCompact`。

**Refactor**：摘要 prompt 抽模板资源。

**DoD**：3 测试绿；长会话不撑爆 context。

---

### 阶段 E7：子 Agent（并行排查）

#### #E7-1 [TDD] SubAgentTool 隔离子循环 （blockedBy: E2-2）

**Red**：`SubAgentToolTest`
- `runsChildExecutorWithNarrowedTools`
- `childDoesNotShareParentConversation`
- `returnsChildFinalTextAsToolResult`
- `propagatesCancellationToChild`

**Green**：实现 DESIGN §9 草图；子 Agent 继承 cwd / 只读 policy / LlmClient，独立 Conversation。

**Refactor**：子 Agent 事件不串入主 stream-json（或加 `subagent` 标记，按前端能力定）。

**DoD**：4 测试绿。

---

### 阶段 E8：可观测

#### #E8-1 [TDD] usage / cost 透出 （blockedBy: E1-2, E4-1）

**Red**：`UsageReportingTest`
- `resultEventCarriesUsage` — result 事件含 `input_tokens` / `output_tokens` / `cache_read_input_tokens`
- `aggregatesUsageAcrossTurns`

**Green**：`LlmClient` 补 usage 透传（若未透传），listener 在 result 事件写入；agent-web 落库到诊断历史。

**DoD**：2 测试绿；诊断历史页可见每次 token / cost。

---

## 5. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 事件契约对不齐前端 `parseStreamJson` | 前端渲染错乱 | E0 先采集真实样本逐字段对齐；E4 用 `DiagnoseFlowTest` 模式做 SSE 集成验证 |
| 依赖冲突（jackson / slf4j / netty vs Spring Boot 3.3 BOM） | agent-web 启动失败 | E4 强制 `mvn dependency:tree`；必要时引擎 shade 或 `<exclusions>` |
| 线程 / 中断协调（agent-web fork 线程跑 runStream） | stop 失灵 / 资源泄漏 | 引擎虚拟线程响应中断；`RunningSessions` 与 `stopStream` 桥接测试覆盖 |
| 工具结果过大撑爆 context | 成本飙升 / 截断关键信息 | E6 截断 + auto-compact；诊断 prompt 引导精准查询 |
| Bash 任意命令在服务端的安全面 | 越权 / 破坏 | MVP 直接 `DENY` Bash；如需放开走只读命令白名单 |

---

## 6. 与原 DESIGN / TASKLIST 的关系

**保留可用**：domain 全层、`AgentExecutor` + `ParallelToolDispatcher`、`LangChain4jLlmClient` + prompt cache、`CancellationToken`、`AgentEventListener`、`FileRead` / `Glob` / `Grep`、`DefaultPermissionPolicy`。

**退役 / 降级**：`interfaces/cli` 全套（`ReplLoop` / `JLine` / `SigintHandler` / `SlashCommand*` / `OutputRenderer`）降为独立调试入口，不删；`FileWriteTool` / `FileEditTool`（诊断只读，不注册）；`FileChatMemoryStore`（集成模式旁路，CLI 调试仍可用）。

**新增**：`interfaces/engine`（门面 + stream-json listener）、`infrastructure/tools` 诊断工具、`ReadOnlyPermissionPolicy`、`ContextCompactionService`、`SubAgentTool`。

**DESIGN.md 待更新**：在 §16 追加本次定位转变决策；§1 边界改写为「引擎而非 CLI」。

---

## 7. 验收（Definition of Done）

1. **MVP**（E1–E4）：agent-web 选 `NATIVE`，浏览器跑通流式诊断对话 + 工具调用展示 + 停止。
2. **诊断可用**（E5–E6）：至少 3 个只读诊断工具接入，长会话不爆 context。
3. **质量门禁**：`mvn clean verify` 绿，`LayeredArchitectureTest` 不破，JaCoCo 不降；每个 TDD 任务三次提交（Red/Green/Refactor）。
4. **打 tag** `v0.2.0-engine`。

---

## 8. 执行顺序建议

```
E0-1
  └→ E1-1 → E1-2 ┐
  E2-1 ──────────┼→ E2-2 ┐
  E3-1 ──────────────────┼→ E4-1 → E4-MVP-Gate   ← MVP 完成线
                          │
                          ├→ E5-1..E5-6（并行，各自独立）
                          │     └→ E6-1 → E6-2
                          ├→ E7-1
                          └→ E8-1
```

MVP 关键路径：**E0-1 → E1 → E2 → E3 → E4**。诊断工具（E5）与增强（E6–E8）在 MVP 跑通后并行推进。
