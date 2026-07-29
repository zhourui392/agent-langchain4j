# AgentKit Kernel：高优先级 Agent Runtime 补齐任务

> 状态：执行中；已纳入 `TASKLIST.md` S9，#40–#41 已完成
>
> 审计日期：2026-07-29
>
> 对照对象：Claude Code、Codex CLI/Agent runtime 的公开流程与当前仓库实现
>
> 适用范围：`agentkit-kernel`；仅在明确标注处涉及组合根或测试基线

## 1. 结论先行

`agentkit-kernel` 已经完成一个可用的 MVP happy path：模型流式输出、多轮 tool-use、权限检查、并发工具执行且保序回填、取消令牌、预算、上下文压缩服务、JSONL 会话、prompt cache、知识型 Skill、`SubAgentTool`、`StructuredAgent`、结构化输出工具以及 OpenAI/Anthropic provider 都已存在。

当前与 Claude Code / Codex 成熟 Agent 流程的首要差距，不是再增加几个工具，而是补齐 **Agent 运行控制面**：一次 run 的作用域、tool-use 生命周期、可靠终态、安全边界、可取消 I/O、上下文策略和可恢复事件。若先做 MCP、后台任务或更复杂的多 Agent 编排，现有的 cwd/cancellation 分裂、悬空 tool-use、伪 terminal 和安全默认值会被复制到每一个新入口。

本文件把高优先级任务定义为：

1. 不完成就可能破坏消息协议、执行到错误 workspace、越权访问或错误恢复；
2. 已经影响 diagnosis/coding 两个 agent 包，而不是未来才可能出现；
3. 是 MCP、通用子 Agent、checkpoint、后台任务等后续能力的共同前置抽象；
4. 可以在不把领域工作流下沉 kernel 的前提下完成。

建议先完成 `#40 → #41 → #42`，再并行推进 `#43/#44`，最后完成 `#45/#46`。在这批任务完成前，不建议把 kernel 宣称为与 Claude Code / Codex 同等级的通用 Agent runtime。

## 2. 文档与任务编号约定

- `#40`–`#46` 已正式写入 `TASKLIST.md` S9；实施状态以该文件为准。
- `#1`–`#39` 的既有完成状态已核对，新增编号没有冲突。
- 所有标记 `[Kernel-TDD]` 的任务继续遵守仓库的 Red → Green → Refactor 三提交纪律。
- 本文不会替代 `DESIGN.md` 的架构决策正本。涉及公开 API、退出语义、安全默认值的最终选择，应同步记录到 `DESIGN.md §16`，注明日期。
- 本文保留审计时的“当前问题与证据”作为历史基线；完成任务后同步更新本页状态、`TASKLIST.md` 与 `DESIGN.md §16`。

## 3. 当前能力与成熟流程对照

| 流程环节 | 当前 kernel | Claude Code / Codex 对照能力 | 判断 |
|---|---|---|---|
| 模型回合 | 已支持流式 LLM → tool batch → result → 下一轮 | 都以可中断、受策略控制的 agent loop 为核心 | happy path 已有 |
| 工具批次 | 多工具用虚拟线程并发，按原 `tool_use` 顺序回填 | 都要求工具调用拥有明确生命周期、错误和批准结果 | 正常路径完整，异常路径会留下悬空调用 |
| 终结语义 | `StructuredOutputTool` 只是普通工具，调用后仍继续请求模型 | 结构化交接/任务完成应形成明确 stop reason | 缺失 |
| Run 作用域 | `ExecutionContext` 固定在 executor/dispatcher，`run` 另收 cancellation | 工作区、审批、会话、沙箱、取消均绑定一次运行 | 分裂，且会传错 cwd/cancellation |
| 权限与隔离 | 有 ALLOW/ASK/DENY，但默认 BYPASS；文件路径可越 workspace | 两者都把审批与 sandbox/workspace policy 分开处理 | 权限存在，执行边界不足 |
| 运行结果 | executor 只返回最后一条 `AiMessage` | stop reason、usage、取消、超时、任务结果都是一等运行结果 | 缺失 |
| LLM 取消 | stream callback 中检查 token；provider 等待固定 90 秒 | 运行取消应终止正在进行的模型/工具 I/O | 未闭环 |
| 上下文 | 有压缩服务，但只有 diagnosis 在 run 前显式调用 | 成熟 runtime 在每轮调用前治理上下文，并可从 overflow 恢复 | 接线分散 |
| 会话恢复 | 保存消息投影，整文件原子重写 | 支持 resume/fork/checkpoint，并区分已完成和 in-flight action | 仅有消息恢复 |
| 子 Agent | 有同步、只读、文本返回的 `SubAgentTool` | 支持角色、模型、预算、取消、跟进和独立生命周期 | 可用原型，后续任务 |
| MCP / hooks / background | MCP 只有空 package；listener 只观察；Bash 同步 | 两者均提供 MCP、生命周期扩展及长任务管理能力 | 非首批，见后续文档 |

成熟度是定性判断，不是兼容性百分比承诺：正常成功路径已经比较完整；异常、scope、安全、恢复和多 Agent 控制面仍处于原型阶段。按本次审计维度，整体约为成熟 Claude Code / Codex runtime 的 **55%–65%**。

## 4. 领域模型审计

### 4.1 评分

| 维度 | 得分 | 主要原因 |
|---|---:|---|
| 聚合边界是否清晰 | 2/3 | `Conversation` 与 `ToolInvocation` 已成形，但一次 `AgentRun` 和一次 `AssistantTurn` 没有成为明确一致性边界 |
| 变化是否被收敛 | 2/3 | provider、permission、tool 已有 port/policy；stop、context、retry、output limit 仍散在 executor、listener 和 agent 包 |
| 不变量是否可被模型守护 | 1/3 | 只守住 result 必须有 request、不能重复 settle；未守住全批次 settle、顺序、terminal 和 run scope |
| 行为是否与模型一致 | 1/3 | `TerminalToolSpec` 的名称表达“终结”，实际仍会继续下一轮；`ExecutionContext` 也未贯穿一次 run |
| 是否支持下一轮需求变化 | 1/3 | MCP、可恢复任务和通用子 Agent 若现在接入，会复制当前作用域与生命周期问题 |
| **合计** | **7/15** | 先修运行时聚合与不变量，再扩功能 |

### 4.2 建议统一语言

| 概念 | 定义 | 不应混入的内容 |
|---|---|---|
| `AgentSpec` | 静态角色配置：system prompt、能力集、模型档位、terminal spec | 某次 run 的 cwd、取消、已消耗预算 |
| `AgentRunContext` | 一次 run 唯一的动态作用域和能力边界 | 跨 run 的全局可变状态 |
| `AgentRun` | 从 RUNNING 到某个 `StopReason` 的生命周期聚合根 | diagnosis/coding 的领域工作流状态 |
| `AssistantTurn` | 一次模型响应及其有序 tool batch | 下一轮模型响应 |
| `ToolInvocation` | turn 内单次工具请求实体，有唯一 ID 和终态 | 仅以文本表示的错误 |
| `ToolResultStatus` | SUCCESS / ERROR / DENIED / CANCELLED / TIMEOUT | provider 专属的布尔字段 |
| `AgentRunResult` | run 的统一终态、最终消息、结构化 payload、usage、预算消耗 | 只返回最后一段自然语言 |
| `WorkspaceBoundary` | kernel 文件工具允许访问的真实路径边界 | 宿主级容器/VM sandbox 的虚假替代品 |
| `CompactionBoundary` | 历史被摘要替换的显式边界和来源范围 | 伪装成普通 user message 的摘要 |

### 4.3 目标边界

```text
AgentSpec + AgentRunContext
          │
          ▼
AgentRuntime / AgentExecutor
 ├─ AgentRun                         ← 运行生命周期聚合根
 │   ├─ Conversation                 ← 消息投影
 │   └─ AssistantTurn[]              ← 强一致 tool batch
 │       └─ ToolInvocation[]         ← 每个必须恰好 settle 一次
 ├─ ContextPolicy
 ├─ PermissionPolicy
 ├─ ToolDispatchPolicy
 ├─ StopPolicy
 └─ RunEventStore
          │
          ▼
    AgentRunResult
```

`AgentExecutor` 仍是 application 编排器，但 run 生命周期和工具批次不变量应由 domain 类型守护；不能继续依赖 application 层若干 `if` 的调用顺序“碰巧正确”。

### 4.4 必须建立的不变量

1. 同一 `AssistantTurn` 内 `ToolUseId` 唯一。
2. 在请求下一轮 LLM 前，当前 turn 的每个 tool-use 必须恰好有一个有序终态 result。
3. SUCCESS、ERROR、DENIED、CANCELLED、TIMEOUT、UNKNOWN_TOOL、INVALID_ARGUMENTS 都算 settled，不能通过抛异常跳过配对。
4. tool result 必须按模型原始 request 顺序进入 conversation/provider 请求。
5. terminal tool 只有在 schema 校验成功后才终结；终结后不得再调用 LLM。
6. 一次 run 内所有 LLM、工具、子 Agent、权限和预算必须使用同一个 `AgentRunContext`。
7. executor 并发运行时不得共享 cwd、cancellation、预算消费、permission cache 或 file-state cache。
8. workspace 外路径必须被执行层拒绝；仅有 permission prompt 不等于路径隔离。
9. child agent 消耗必须计入 parent 的总预算，并受深度/并发上限约束。
10. compaction 不得制造孤儿 tool result，也不得在摘要失败时静默删除历史。
11. resume 不得重执行已经 settled 的工具；对中断时仍 in-flight 的调用必须显式标记为 UNKNOWN/CANCELLED，不能猜测成功。

## 5. 建议依赖顺序

```text
构建基线修复
    │
    ▼
#40 AgentRunContext
    ├──────────────┬──────────────────┐
    ▼              ▼                  ▼
#41 Tool batch   #43 Workspace      #44 LlmCall
    │              boundary           cancellation
    ▼                                  │
#42 RunResult + terminal                │
    ├───────────────────────────────────┘
    ▼
#45 ContextPolicy + ToolOutputPolicy
    │
    ▼
#46 RunEventStore + safe resume
```

- `#41` 必须在 `#42` 前完成，因为 terminal 也必须先满足整批配对。
- `#43` 依赖 `#40` 提供 workspace scope，但可与 `#41/#42` 的主体并行。
- `#44` 依赖 `#40` 提供 run-scoped timeout/cancellation。
- `#45` 依赖 `#44`，否则 compaction LLM 本身仍不可取消。
- `#46` 最后固化事件模型，避免先把错误的生命周期持久化。

## 6. 进入任务前的基线门禁

### GATE-0 [Test] 恢复跨平台全量构建

**问题**

审计时执行 `mvn -B -ntp clean verify`，kernel 278 个测试中 277 通过、1 个失败：`SessionPathsTest` 在 Linux 上把 `D:\home` 当作普通路径字符串，却断言 Windows 分隔符。排除该测试后，CLI 的 `AgentKitApplicationTest.historyFileUsesUserHome` 还有同类失败。因此当前分支不能把全量构建绿色作为后续重构的可信基线。

**证据**

- [`SessionPathsTest.java`](../agentkit-kernel/src/test/java/com/anthropic/agentkit/infrastructure/memory/SessionPathsTest.java) 第 16–21 行。
- [`AgentKitApplicationTest.java`](../agentkit-cli/src/test/java/com/anthropic/agentkit/interfaces/cli/AgentKitApplicationTest.java) 第 85–93 行。

**范围**

- 测试按当前 OS 的 `Path` 语义断言，不在 Linux 上硬编码 Windows separator。
- 保持 `SessionPaths` 和 history path 的生产行为不变。
- 这是后续 `#40` 的前置门禁，不占用 kernel runtime 的候选任务编号。

**DoD**

- Linux/JDK 21 上 `mvn -B -ntp clean verify` 全绿。
- 测试仍验证路径基于 `user.home`，不是删掉或弱化为只断言非空。
- 若 CI 包含 Windows runner，同一测试在 Windows 也通过。

## 7. 高优先级任务详情

### #40 [Kernel-TDD] `AgentRunContext` 单一作用域 + `AgentExecutor` 可重入

**状态**：已完成（2026-07-29）。

**Goal**

让一次 run 的 workspace、取消、预算消费、权限缓存和事件关联拥有单一事实来源；`AgentExecutor` 只持无状态配置，可被多个 run 并发复用。

**当前问题与证据**

- [`AgentExecutor.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/application/AgentExecutor.java) 第 55–64 行在构造期把固定 `ExecutionContext` 捕获进 dispatcher；第 83–91 行的 `run` 又单独接收 `CancellationToken`。
- 同文件第 37–52、240–242 行的便利构造器隐式读取 `user.dir`，并创建另一枚 cancellation。
- [`StructuredAgent.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/infrastructure/agent/StructuredAgent.java) 的 `run(task, ctx)` 只把 `ctx.cancellation()` 交给 executor；工具仍可能使用默认 cwd 和另一份 context。
- 当前 `AgentBudgetGuard` 在每次 `runLoop` 创建，但 budget 配置、permission decision cache、`FileStateCache` 的预期 scope 没有统一模型。

**建议模型**

```java
record AgentRunContext(
        RunId runId,
        SessionId sessionId,
        WorkspaceId workspaceId,
        Path workspaceRoot,
        CancellationToken cancellation,
        RunBudget budget,
        RunEventSink events) {}
```

字段可按最小实现分步加入，但必须先确立以下语义：

- `AgentSpec`/executor 构造参数是静态配置；`AgentRunContext` 是动态状态。
- dispatcher 在每次 run 内创建，或每次 dispatch 显式接收 context；不能把 context 固化为 executor 字段。
- `PermissionDecisionCache` 明确是 run scope 或 session scope，不得以 executor 实例生命周期代替。
- `FileStateCache` 至少按 `(WorkspaceId, session/run)` 隔离。
- 组合根可提供 `AgentRunContext.forWorkspace(...)` 便利工厂，但 kernel 内部不读取 `user.dir`。

**Red**

- `structuredAgentUsesProvidedWorkingDirectory`
- `runCancellationReachesTools`
- `concurrentRunsDoNotShareCwdOrCancellation`
- `concurrentRunsDoNotShareBudgetConsumption`
- `permissionAllowAlwaysDoesNotLeakAcrossRuns`
- `fileStateCacheDoesNotAuthorizeAnotherWorkspace`

测试继续复用 `StubLlmClient` / `FakeTool`，并用两个不同临时目录验证真正传到 `Tool.execute` 的 context。

**Green**

- 引入最小 `AgentRunContext`、`RunId`、`WorkspaceId`。
- 将 `AgentExecutor.run` 收敛为接收 context；保留旧 overload 时只允许它们在明确的兼容层委托，并标注迁移计划。
- `ParallelToolDispatcher` 不再持有跨 run 的 `ExecutionContext`。
- `StructuredAgent`、`SubAgentTool`、diagnosis/coding 入口完整透传同一 scope。
- 从 kernel executor 默认构造器移除 allow-all + `user.dir` 的隐式组合。

**Refactor**

- 区分 `AgentRuntimeConfig`（静态）和 `AgentRunContext`（动态），避免 context 继续膨胀成杂物袋。
- 把 run-scoped mutable state 收敛到私有 `AgentRunState`，不让 port 签名暴露可随意修改的集合。
- 更新 `DESIGN.md §16.7` 中“暂不做 cwd 透传”的决定；coding 已经是第二个 agent 包，原延后条件已失效。

**DoD**

- 一个 executor 实例可并发执行两个 workspace，测试稳定重复 100 次。
- 所有工具、子 Agent、权限和 listener/event 都能关联相同 `RunId`。
- kernel runtime 代码不再读取 `System.getProperty("user.dir")`。
- 不引入全局单例 context 或 `ThreadLocal` 作为事实来源；MDC 只用于日志投影。

**blockedBy**：GATE-0。

---

### #41 [Kernel-TDD] `AssistantTurn` / tool batch 完整 settle 不变量

**状态**：已完成（2026-07-29）。

**Goal**

把“一次模型产生的所有 tool-use 必须有序且恰好 settle 一次”建模成强不变量，并让所有失败路径都产生结构化 `ToolResultMessage`。

**当前问题与证据**

- [`ToolUseInvariantChecker.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/domain/conversation/ToolUseInvariantChecker.java) 只检查 result 有已知 request、同一 ID 不重复 settle；注册时用 `Set.add`，不会拒绝同 batch 重复 ID。
- [`AgentExecutor.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/application/AgentExecutor.java) 第 129–138 行先 append assistant tool-use，再 reserve tool-call budget。预算异常会留下 pending tool-use。
- [`ParallelToolDispatcher.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/application/ParallelToolDispatcher.java) 第 87–98 行的未知工具、非法 JSON、permission/listener 异常可能在生成 result 前跳出整批。
- 同文件第 139–148 行虽然按 request 顺序组装，但假设 `resultsById` 中一定存在结果；任一 worker 失败可让整个 assemble 不可达。
- [`ToolResult.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/domain/tool/ToolResult.java) 有 `success`，但 [`ToolResultMessage.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/domain/message/ToolResultMessage.java) 只保留 ID + text，provider 无法结构化映射 `is_error`。

**建议模型**

```java
enum ToolResultStatus {
    SUCCESS, ERROR, DENIED, CANCELLED, TIMEOUT,
    UNKNOWN_TOOL, INVALID_ARGUMENTS, BUDGET_EXHAUSTED
}

final class AssistantTurn {
    TurnId id;
    AiMessage response;
    List<ToolInvocation> invocations; // model order
    TurnState state;                  // RECEIVED, DISPATCHING, SETTLED
}
```

不强制一开始就把 `Conversation` 的存储结构全部改写，但不变量的所有权必须明确落在 domain，而不是 dispatcher 的偶然执行顺序里。

**Red**

- `rejectsDuplicateToolUseIdWithinAssistantTurn`
- `rejectsNextAssistantMessageWhileToolBatchPending`
- `rejectsNextUserMessageWhileToolBatchPending`
- `budgetExhaustionSettlesEveryToolUseInOrder`
- `unknownToolReturnsOrderedErrorResult`
- `invalidArgumentsReturnOrderedErrorResult`
- `permissionFailureSettlesInvocationAsError`
- `listenerFailureCannotOrphanToolInvocation`
- `cancelledParallelBatchSettlesEveryAcceptedRequest`
- `toolErrorStatusSurvivesConversationAndProviderMapping`

**Green**

- `ToolResultMessage` 携带 `ToolResultStatus` 与最小 metadata，不再丢失错误语义。
- dispatcher 对每个 request 建立独立 outcome；单个工具失败不会让同批其他结果消失。
- UNKNOWN_TOOL、INVALID_ARGUMENTS、DENIED、TIMEOUT、CANCELLED 等均转成 result。
- 组装阶段以 request 列表为准，缺 outcome 本身是 domain invariant violation，并在写入 conversation 前被检测。
- 预算拒绝工具执行时，仍为整批生成 `BUDGET_EXHAUSTED` result；不得 append 请求后直接抛出。
- listener 的观察异常默认隔离并记录，不允许破坏协议；若未来 interceptor 可以阻断，必须返回明确 outcome。

**Refactor**

- 让 `ToolInvocation` 状态机覆盖 DENIED/CANCELLED/TIMEOUT，不用 `boolean success` + exception 双轨表达同一事实。
- 明确 `ToolBatchDispatcher` 的合同：输入有序 invocation，输出等长、有序、全部 terminal 的 outcome。
- provider adapter 只负责将通用 status 映射为 Anthropic/OpenAI 协议字段。

**DoD**

- 任一可预期工具失败都不会产生孤儿 `tool_use`。
- 下一轮 LLM 只会看到完整、有序、带错误状态的 result batch。
- 多工具并发及异常注入测试重复 100 次稳定。
- JSONL session codec 能往返新的 status；旧 session 有清晰的向后兼容默认值。

**blockedBy**：#40。

---

### #42 [Kernel-TDD] `AgentRunResult` + 原生 terminal-tool 退出

**Goal**

让 run 以正式结果结束，并让成功的 terminal tool 成为真正的停止条件，不再依赖模型追加一轮自然语言“done”。

**当前问题与证据**

- `AgentExecutor.run` 只返回 `CompletableFuture<AiMessage>`，stop reason、usage、turn/tool 计数和预算消费分散在异常、listener、日志及 diagnosis `RunSummary` 中。
- [`StructuredAgentTest.java`](../agentkit-kernel/src/test/java/com/anthropic/agentkit/infrastructure/agent/StructuredAgentTest.java) 当前在 terminal tool 响应后还需 enqueue 第二条无工具消息才能结束，证明 terminal 只是普通工具。
- [`StructuredOutputTool.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/infrastructure/tools/StructuredOutputTool.java) 第 67–76 行只检查 required 字段是否出现，没有完整验证字段类型、enum、嵌套结构和 additionalProperties。

**建议结果类型**

```java
record AgentRunResult(
        RunId runId,
        StopReason stopReason,
        AiMessage finalMessage,
        Optional<Map<String, Object>> structuredOutput,
        Usage usage,
        BudgetConsumption budget,
        int turns,
        int toolCalls) {}
```

`StopReason` 至少覆盖：

- `MODEL_COMPLETED`
- `TERMINAL_TOOL`
- `WAITING_FOR_INPUT`
- `WAITING_FOR_APPROVAL`
- `CANCELLED`
- `TIMED_OUT`
- `BUDGET_EXHAUSTED`
- `CONTEXT_EXHAUSTED`
- `PROVIDER_ERROR`
- `TOOL_PROTOCOL_ERROR`

本任务只实现当前能产生的 reason；WAITING 状态可先保留枚举和协议位置，交互式恢复见后续文档 `#51`。

**Terminal 不变量**

- 只有 schema 校验成功且 sink/consumer 接受 payload 的 terminal result 才能停止 run。
- terminal result 仍必须写入 conversation/event log，保证 tool-use 配对完整。
- terminal 成功后不得再请求 LLM。
- 为避免同一批中 terminal 成功后还有副作用工具，terminal invocation 必须独占一个 tool batch；混用或多个 terminal 视为 protocol error，并为整批 settle 错误结果。
- terminal 校验失败只产生 ERROR result，允许模型下一轮纠正参数，不算成功终结。

**Red**

- `terminalToolStopsWithoutSecondLlmCall`
- `terminalResultIsAppendedBeforeRunStops`
- `failedTerminalValidationDoesNotStopRun`
- `mixedTerminalAndNormalToolsAreRejectedWithoutSideEffects`
- `multipleTerminalCallsInOneBatchAreRejected`
- `runResultReportsModelCompleted`
- `runResultReportsTerminalPayloadAndUsage`
- `runResultReportsBudgetExhaustionWithoutOrphanedToolUse`
- `structuredOutputRejectsWrongTypeAndUnknownProperty`

**Green**

- 以 `ToolKind.TERMINAL`、`TerminalTool` marker 或等价类型安全方案表达终结能力；不要在 executor 中硬编码工具名。
- executor 返回 `CompletionStage<AgentRunResult>`。
- 收敛 usage/turn/tool/budget 计数，listener 只做投影，不再是取结果的唯一渠道。
- 接入可维护的 JSON Schema validator，或定义 kernel 支持的明确 schema 子集并完整验证该子集。
- `StructuredAgent` 直接读取 `AgentRunResult.structuredOutput`，移除需要第二条模型响应的 sink 时序依赖。

**Refactor**

- diagnosis 的 `RunSummary/ExitReason` 与 kernel `AgentRunResult/StopReason` 建立显式 adapter，避免两个相似终态模型继续漂移。
- 公开 API 保留迁移层时，明确 deprecated 周期；不要长期同时维护 `AiMessage` 和 `AgentRunResult` 两套 run 合同。

**DoD**

- terminal 成功场景 LLM 调用次数精确为 1。
- terminal payload 只接受一次，不能被后续调用覆盖。
- 预期运行终态都能从 `AgentRunResult` 判断，不需要解析异常文本或日志。
- 所有 provider、StructuredAgent、diagnosis/coding 回归测试通过。

**blockedBy**：#41。

---

### #43 [Kernel-TDD] `WorkspaceBoundary` + 参数级权限 + 安全默认值

**Goal**

在 L0 单用户/worktree 范围内建立真实可执行的 workspace 文件边界，并把 permission 从“按工具名记住”升级为 scope-aware、argument-aware 规则。

**当前问题与证据**

- [`ConfigLoader.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/infrastructure/config/ConfigLoader.java) 第 21–25 行默认 `PermissionMode.BYPASS`。
- [`AgentExecutor.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/application/AgentExecutor.java) 的便利构造器默认 allow-all，`StructuredAgent` 会走到该路径。
- `FileReadTool` / `FileWriteTool` / `FileEditTool` 仅 `ctx.cwd().resolve(path).normalize()`，没有验证 real path 仍在 workspace root。`../`、绝对路径和 symlink 可越界。
- `BashTool` 只设置进程 cwd，不构成文件系统 sandbox；shell 仍可访问宿主可见路径。
- [`PermissionDecisionCache.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/application/PermissionDecisionCache.java) 只缓存 tool name；一次“always allow Bash”会放行后续任意 Bash 参数。
- [`ConfigLoader.fromSystem`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/infrastructure/config/ConfigLoader.java) 直接使用 `System::getenv`，与 AGENTS.md 的 `SecretProvider` 承重缝不一致。

**安全边界说明**

本任务不声称用 Java 路径检查替代容器/VM：

- 对 kernel 自带文件工具，`WorkspaceBoundary` 是必须由执行层强制的硬路径边界。
- 对 Bash，L0 只能提供显式审批、命令级规则、环境最小化、timeout 和审计；它不是宿主隔离。
- 多用户 L2 仍必须升级为容器/VM + 鉴权，符合 `AGENTS.md` 的既定边界；本任务不引入容器调度。

**建议抽象**

```java
interface WorkspaceBoundary {
    Path resolveExisting(Path requested);
    Path resolveForCreate(Path requested);
}

record PermissionRule(
        ToolSelector tool,
        ArgumentPredicate arguments,
        ScopeSelector scope,
        Decision decision) {}
```

规则优先级固定为 `DENY > ASK > ALLOW`；“always allow”必须缓存规范化后的规则/参数范围，并绑定 WorkspaceId 或 session，不得只缓存工具名。

**Red**

- `readCannotEscapeWorkspaceThroughDotDot`
- `writeCannotEscapeWorkspaceThroughAbsolutePath`
- `writeCannotEscapeWorkspaceThroughSymlinkedParent`
- `editRejectsSymlinkTargetOutsideWorkspace`
- `globAndGrepCannotTraverseOutsideWorkspace`
- `allowAlwaysForOneBashPatternDoesNotAllowAnotherCommand`
- `permissionCacheDoesNotLeakAcrossWorkspaceOrRun`
- `denyRuleOverridesAllowRule`
- `defaultConfigurationDoesNotBypassPermissions`
- `toolObtainsSecretOnlyThroughScopedSecretProvider`

**Green**

- `AgentRunContext` 持有 `WorkspaceId` 和规范化 workspace root。
- 所有 kernel 文件工具统一通过 `WorkspaceBoundary`，分别处理“已存在目标”和“待创建目标的最近已存在父目录”。
- 对 symlink、绝对路径、`..` 和不同 filesystem root 有明确拒绝语义。
- 默认 permission mode 改为安全交互模式；BYPASS 必须由调用者显式配置。
- permission cache 按 scope + tool + argument rule 存储。
- 在 domain 定义 `SecretProvider` port；环境变量读取只存在于 infrastructure adapter/组合根，工具不得直接读全局环境。

**Refactor**

- 提取平台无关的路径 policy 与平台相关 resolver，Windows drive/UNC 和 Linux symlink 分别测试。
- `isReadOnly()` 只作为权限输入之一，不把“只读”误当作“可访问任意宿主路径”。
- 记录 Bash 无 sandbox 的显式 residual risk，避免文档承诺超过实现能力。

**DoD**

- kernel 自带文件读取/修改工具不能经 `..`、绝对路径或 symlink 越出 workspace。
- 默认启动不会静默进入 BYPASS。
- 所有 permission 决策能关联 `RunId/WorkspaceId`，审计记录不包含明文 secret。
- Linux 与 Windows 路径测试均使用各自真实 Path 语义。

**blockedBy**：#40。

---

### #44 [Kernel-TDD] 可取消 `LlmCall` + run-scoped timeout/output budget

**Goal**

让取消和超时能终止正在进行的 LLM I/O，而不只是等下一段 token；统一模型、工具和子 Agent 的 run-scoped 限制。

**当前问题与证据**

- [`LangChain4jLlmClient.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/infrastructure/llm/LangChain4jLlmClient.java) 第 130–140 行固定等待 90 秒，调用方拿不到可取消 handle。
- cancellation 主要在 `AgentExecutor` 轮次开始和 `onPartialText` 中检查；如果 provider 长时间无 delta，token 无法触发主动中断。
- timeout 后 provider 仍可能晚到 `onComplete`/`onError`，当前没有单一 completion guard。
- `AgentBudget` 关注 turn/tool/input token，但缺少一致的 wall-clock deadline、output token/char 上限和 child agent 配额继承。

**建议端口**

```java
interface LlmCall {
    CompletionStage<LlmResponse> completion();
    boolean cancel();
}

interface LlmClient {
    LlmCall streamChat(ChatRequest request, StreamHandler handler);
}
```

具体返回类型可调整，但必须具备：取消、唯一终态、timeout、usage 回收以及 late callback 隔离。

**Red**

- `cancelsLlmCallBeforeFirstDelta`
- `timeoutCancelsProviderRequest`
- `lateCompletionAfterTimeoutIsIgnored`
- `completionAndErrorCanOnlyWinOnce`
- `outputTokenBudgetStopsRunWithExplicitReason`
- `toolTimeoutSettlesInvocationAsTimeout`
- `childAgentCannotExceedParentDeadlineOrBudget`

**Green**

- `LlmClient` 返回 call handle/completion，不再由 adapter 内部阻塞固定 90 秒作为唯一控制手段。
- `AgentRunContext` 提供 deadline/limits；provider adapter 将取消尽可能传到底层 SDK。
- executor 用统一原子状态机竞争 COMPLETE / ERROR / CANCEL / TIMEOUT。
- timeout/cancel 映射到 `AgentRunResult.StopReason`，若已经产生 tool-use 则先按 `#41` settle。
- 工具 governance 与子 Agent 继承 parent deadline，并可使用更小的 child budget，不能放大 parent 配额。

**Refactor**

- 区分 provider request timeout、run deadline、tool timeout，错误信息和 stop reason 不混用。
- output 限制只计量一次，避免 stream delta 和最终 message 重复累计。

**DoD**

- 无 token 输出的挂起模型调用可在 deadline 内被取消。
- timeout 后不会出现第二次完成、late event 污染已结束 run 或未释放 latch/thread。
- 所有等待均来自 context/config，不存在不可配置的 runtime 魔法常量。

**blockedBy**：#40、#42。

---

### #45 [Kernel-TDD] `ContextPolicy` + 全局 `ToolOutputPolicy`

**Goal**

让每个 agent 入口、每一轮 LLM 调用都执行相同的上下文与大输出治理，并能在 provider context overflow 后安全恢复。

**当前问题与证据**

- [`ContextCompactionService.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/application/context/ContextCompactionService.java) 已实现阈值压缩，但只有 diagnosis `DefaultDiagnoseEngine` 在 run 开始前调用；CLI、coding、`StructuredAgent` 和每轮 loop 没有统一接线。
- summary 被写成普通 `UserMessage("[Earlier conversation summary]...")`，没有 `CompactionBoundary` 类型或来源范围。
- summarizer transcript 只渲染 role + text，没有可靠保留 tool name/args/result status；summarizer 未返回消息时用空字符串重建，会静默丢历史。
- `TruncatingTool` / governance 是装饰器能力，不保证每个新注册工具都经过统一输出限制。

**建议策略端口**

```java
interface ContextPolicy {
    ContextDecision beforeLlmCall(Conversation conversation, AgentRunContext context);
    ContextDecision recoverFromOverflow(Conversation conversation, Throwable overflow,
                                        AgentRunContext context);
}

interface ToolOutputPolicy {
    ToolOutput govern(ToolInvocation invocation, ToolResult raw,
                      AgentRunContext context);
}
```

**Red**

- `compactsBeforeEveryLlmCallWhenThresholdReached`
- `structuredAndCodingAgentsUseSameContextPolicy`
- `contextOverflowCompactsOnceAndRetriesSafely`
- `compactionNeverSplitsToolBatch`
- `compactionPreservesToolNameArgumentsAndResultStatus`
- `summarizerFailurePreservesOriginalConversation`
- `emptySummaryCannotReplaceHistory`
- `everyRegisteredToolOutputIsGloballyLimited`
- `truncatedOutputIncludesStableArtifactReferenceOrExplicitOmission`

**Green**

- executor 在每次 LLM call 前委托 `ContextPolicy`，agent 包不再各自决定是否 compact。
- provider 明确识别 context overflow 时至多执行一次 reactive compact + retry，避免无限循环。
- 引入显式 `CompactionBoundary` 或等价 event/projection，记录被替换范围、摘要版本和 token 估算。
- compaction 以完整 assistant/tool batch 为最小保留单元。
- 摘要失败时保留原 conversation 并返回 `CONTEXT_EXHAUSTED`/明确错误，不能用空摘要丢历史。
- `ToolOutputPolicy` 位于 dispatch 必经路径；所有工具（包括 MCP/未来工具）自动受限。

**Refactor**

- 将 token estimator、保留窗口和 overflow retry 收敛为策略组合，避免 executor 出现 provider 特定分支。
- 完整输出 artifact store 若本任务过大，可先定义 port + omission metadata，把持久实现放到后续 `#50`。

**DoD**

- diagnosis、coding、CLI、StructuredAgent 走同一 context policy。
- compact 前后 tool-use 配对不变量均成立。
- 大结果不会无限进入模型上下文，且模型能区分“完整结果”“截断结果”“外部 artifact”。
- compaction LLM 同样服从 #44 的 cancellation/deadline/usage 统计。

**blockedBy**：#41、#42、#44。

---

### #46 [Kernel-TDD] append-only `RunEventStore` + 安全 resume

**Goal**

用运行事件作为事实记录、Conversation 作为消息投影，使进程中断后能判断哪些 invocation 已完成、哪些处于未知状态，并避免恢复时重放副作用。

**当前问题与证据**

- [`FileChatMemoryStore.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/infrastructure/memory/FileChatMemoryStore.java) 第 49–58 行每次 save 都把完整 message 列表原子重写；文件扩展名虽是 JSONL，语义不是 append-only run log。
- 当前持久化只有消息，没有 RunId、stop reason、usage、compaction、permission decision、in-flight invocation 或 terminal payload。
- `/resume` 的“不重跑工具”依赖只加载历史消息，但进程若恰好在工具执行和 result 持久化之间崩溃，系统无法判断外部副作用是否已经发生。

**建议事件最小集**

```text
RunStarted
UserMessageAccepted
LlmCallStarted / LlmCallCompleted
AssistantTurnReceived
ToolInvocationStarted
ToolInvocationSettled
CompactionCompleted
RunStopped
```

事件必须包含 `RunId`、`SessionId`、`WorkspaceId`、顺序号和版本。secret、完整敏感参数和大输出只记录脱敏摘要/artifact reference。

**Red**

- `appendsRunEventsWithoutRewritingExistingLog`
- `rebuildsConversationProjectionFromEvents`
- `resumeDoesNotReexecuteSettledToolInvocation`
- `resumeMarksStartedButUnsettledInvocationAsUnknown`
- `terminalPayloadAndStopReasonSurviveResume`
- `compactionBoundarySurvivesProjectionRebuild`
- `ignoresOnlyTruncatedFinalRecordAndRejectsMidLogCorruption`
- `eventSequenceIsMonotonicPerRun`

**Green**

- domain 定义 `RunEvent` 和 `RunEventStore` port；文件实现 append-only，并有 schema/version 字段。
- `Conversation`、`AgentRunResult`、usage 等由 event projection 重建。
- 已 settled invocation 永不自动重执行。
- 只有 started 而未 settled 的 invocation 恢复为 UNKNOWN/NEEDS_RECONCILIATION；kernel 不猜测外部副作用结果。
- 保留 `ChatMemoryStore` 兼容 adapter/迁移工具，不能让旧 session 突然不可读。
- 本任务只承诺 kernel 管理的文件编辑可在未来接 checkpoint；Bash、MCP、远端数据库等副作用不承诺通用回滚。

**Refactor**

- 事件写入与 listener 分离：listener 可失败且不影响事实存储；event store 失败则按明确持久化 policy 终止 run。
- 建立 event schema compatibility 测试，避免 record 字段调整直接破坏旧日志。

**DoD**

- kill/restart 故障注入下，恢复不会重复执行已完成工具。
- run 的 stop reason、usage、terminal payload 和 compaction 信息可完整恢复。
- event log 持续 append，不因消息数增长反复重写全部历史。
- 明确记录“不可判断是否完成”的副作用，而不是静默重试。

**blockedBy**：#41、#42、#45。

## 8. 分阶段验收门

### Gate A：协议与终态

完成 `#40/#41/#42` 后，应满足：

- 同一 executor 可并发安全运行多个 workspace；
- 所有 tool batch 在所有退出路径都有序 settle；
- terminal 成功后零额外 LLM call；
- 入口统一获得 `AgentRunResult`。

这是进入 MCP、通用子 Agent和后台任务前的最低门槛。

### Gate B：安全与可取消性

完成 `#43/#44` 后，应满足：

- kernel 文件工具不能越 workspace；
- BYPASS 不再是隐式默认；
- LLM/tool/child agent 都受同一 deadline/cancellation/budget 控制；
- 文档清楚声明 Bash 在 L0 不等于 sandbox。

### Gate C：上下文与恢复

完成 `#45/#46` 后，应满足：

- 所有入口的每轮 LLM 都经过统一 context policy；
- compaction 失败不丢历史；
- 进程重启不重跑已 settled 工具；
- RunEvent 可以重建 Conversation 和 AgentRunResult。

## 9. 明确不下沉 kernel 的内容

以下能力即使 Claude Code / Codex 具备，也不应因此放入 kernel：

- diagnosis 的“假设 → 取证 → 更新计划”；
- coding 的“plan → patch → review → retry”；
- `ReviewVerdict`、`DiagnosisPlan`、`Patch` 等领域 VO；
- worktree 分支命名、合并、冲突解决策略；
- JLine/Web/IDE UI 和 slash command 交互细节；
- 浏览器、GitHub、数据库、Dubbo、ES 等具体工具；
- 容器调度、队列、多租户数据库和鉴权控制面；
- Skill 脚本自动执行；
- peer 黑板式自由协作。

kernel 只提供运行时 primitive、policy 和 port；领域 agent 包拥有自己的 orchestrator 和业务不变量。

## 10. 对照资料

Claude Code 官方资料：

- [How Claude Code works](https://code.claude.com/docs/en/how-claude-code-works)
- [Permissions](https://code.claude.com/docs/en/permissions)
- [Subagents](https://code.claude.com/docs/en/sub-agents)
- [Hooks](https://code.claude.com/docs/en/hooks)
- [MCP](https://code.claude.com/docs/en/mcp)
- [Sessions](https://code.claude.com/docs/en/sessions)
- [Checkpointing](https://code.claude.com/docs/en/checkpointing)
- [Context window](https://code.claude.com/docs/en/context-window)
- [Tools reference](https://code.claude.com/docs/en/tools-reference)

Codex 官方资料：

- [Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents)
- [Approvals and security](https://learn.chatgpt.com/docs/agent-approvals-security)
- [AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Hooks](https://learn.chatgpt.com/docs/hooks)
- [MCP](https://learn.chatgpt.com/docs/extend/mcp)
- [Git worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees)
- [Developer commands](https://learn.chatgpt.com/docs/developer-commands?surface=cli)

这些资料用于提取成熟 runtime 的流程能力，不代表本项目要逐项复制产品 UI 或平台功能。
