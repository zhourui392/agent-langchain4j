# AgentKit Kernel：非高优先级与后续 Agent Runtime 任务

> 状态：`#47–#55` 已纳入 `TASKLIST.md` S10 并开始交付；`#56` 保持条件触发候选
>
> 审计日期：2026-07-29
>
> 前置文档：[`agentkit-kernel-high-priority-tasks.md`](agentkit-kernel-high-priority-tasks.md)
>
> 定位：“非高优先级”表示应后置，不表示低价值或永不实施

## 1. 为什么这些任务后置

本文件覆盖 Claude Code / Codex 成熟流程中确实存在、且 AgentKit 未来大概率需要的能力：通用子 Agent runtime、可干预 lifecycle、MCP、后台任务、人机等待态、高级 checkpoint、model/retry policy、Agent 发现和 CLI 组合根修复。

它们没有进入首批高优先级，原因只有一个：审计时都依赖尚未稳定的运行时语义。若当时直接实现：

- 通用子 Agent 会继承错误的 cwd/cancellation，并可绕过 parent budget；
- MCP 会把未知工具、timeout 和 permission 异常放大为悬空 tool-use；
- background task 会让“工具何时算 settled”更加模糊；
- hooks 会在没有明确失败语义时破坏协议；
- checkpoint 会持久化一个无法区分 completed/in-flight 的消息快照；
- AgentManifest 会把当前组合根缺陷扩散到更多 agent。

`#40–#46` 已于 2026-07-29 完成，Gate A–C 的技术依赖现在均已满足。本文任务仍不自动升级为高优先级：是否立项改由真实宿主、第二个消费方、可观测故障频率或规模瓶颈触发；各任务保留 `blockedBy` 作为已满足的历史依赖与回归门禁。

## 2. 编号与优先级

- `#47`–`#55` 已正式纳入 `TASKLIST.md` S10，实施状态以该文件为准。
- `#56` 保持候选 ID，只有日志规模、多 writer 或 retention 需求触发时再立项。
- `P2`：高优先级 Gate A–C 完成后，能明显提升通用 Agent runtime 能力。
- `P3`：平台/产品成熟度任务，需有真实宿主或第二种接入需求再实施。
- 所有 `[TDD]` 任务继续执行 Red → Green → Refactor 三提交。
- `#54/#55` 不是纯 kernel 任务，文中显式标注为 Platform/CLI，避免职责误下沉。

## 3. 建议顺序

```text
高优先级 Gate A/B/C
        │
        ├─→ #47 AgentSpec + SubAgentRuntime
        │        └─→ #50 Background TaskHandle
        │
        ├─→ #48 AgentInterceptor
        │        └─→ #49 MCP lifecycle/adapters
        │
        ├─→ #51 Waiting/Input/Approval states
        │
        ├─→ #52 Advanced checkpoint/fork/rewind
        │
        ├─→ #53 Model/Retry policy
        │
        └─→ #56 Event index/retention/writer fencing

#47 + 第二个可派发 agent 入口
        └─→ #54 AgentManifest / coding entry point
                 └─→ #55 CLI composition cleanup
```

建议交付顺序：`#47 → #48 → #49 → #50/#51 → #52/#53 → #54/#55`；`#56` 独立按日志规模或多 writer 需求触发。如果近期没有 MCP 宿主，可先做 `#47`，不要为了“对齐产品功能表”提前实现没有消费方的扩展。

## 4. 变化点地图

| 变化来源 | 当前分散位置 | 推荐收敛点 | 对应任务 |
|---|---|---|---|
| 角色 prompt、工具、模型、预算 | `StructuredAgent` 构造参数、`SubAgentTool` 固定逻辑、各 agent 包 | `AgentSpec` + `SubAgentRuntime` | #47 |
| 运行前后干预 | `AgentEventListener`、permission、各入口手工调用 | typed `AgentInterceptor` | #48 |
| 外部工具服务器 | 空 `infrastructure/mcp` package、全量 `ToolRegistry.specs()` | `McpToolAdapter` + server/catalog lifecycle | #49 |
| 长命令和大输出 | 同步 Bash、一次性结果字符串 | `TaskHandle` + artifact/output store | #50 |
| 用户问题和计划批准 | permission ASK、CLI prompt、自然语言约定 | `RunSuspension` / waiting stop reasons | #51 |
| fork/rewind/checkpoint | `ChatMemoryStore` 消息快照 | event-log branch + checkpoint provider | #52 |
| provider retry/fallback | provider factory、异常文本、调用入口 | `ModelPolicy` / `RetryPolicy` | #53 |
| Agent 发现与派发 | 手工 wiring、模块专属 builder | `AgentManifest`/registry（平台层） | #54 |
| CLI 命令和取消接线 | `AgentKitApplication.main`、slash commands | CLI composition root | #55 |
| 事件日志规模与多 writer | 文件 append 前全量读取校验、实例内同步、无 retention | tail index + writer fencing + retention/rotation policy | #56 |

## 5. 后续任务详情

### #47 [Kernel-TDD, P2] `AgentSpec` + `SubAgentRuntime` / `SubAgentHandle`

**状态**：已完成（2026-07-29）；Red/Green/Refactor 三提交交付，实施状态以 `TASKLIST.md` 为准。

**Goal**

把当前同步、只读、文本返回的 `SubAgentTool` 升级为领域无关的子 Agent primitive；角色通过组合注入，不通过子类或 prompt 自觉约束。

**当前问题与证据**

- [`SubAgentTool.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/infrastructure/tools/SubAgentTool.java) 固定工具名 `Task`，输入只有 `prompt`，使用同一 LLM、fresh conversation、固定只读 policy，并同步等待文本结果。
- 没有 role system prompt、model tier、child budget、usage、terminal payload、handle、follow-up、resume、depth/concurrency limit 或 worktree isolation。
- javadoc 仍写“chase one hypothesis”，把 diagnosis 语言泄漏进通用 kernel。
- `StructuredAgent` 已把角色部分配置物化，但没有统一静态 spec 和运行 handle。

**建议模型**

```java
record AgentSpec(
        AgentId id,
        String systemPrompt,
        ToolCapabilitySet allowedTools,
        ModelTier modelTier,
        AgentBudget budget,
        Optional<TerminalToolSpec> terminalTool) {}

interface SubAgentRuntime {
    SubAgentHandle spawn(AgentSpec spec, String task, AgentRunContext parent);
}

interface SubAgentHandle {
    AgentId id();
    AgentRunState state();
    CompletionStage<AgentRunResult> result();
    void followUp(String message);
    boolean cancel();
}
```

**关键不变量**

- child 的工具集合是 parent 能力集合的子集，不能升级权限。
- child deadline 不晚于 parent deadline；child budget 消耗计入 parent 总预算。
- depth 和并发上限由 runtime 强制，不能靠 prompt。
- child 有独立 conversation/runId，但共享显式 workspace boundary 或使用明确 child worktree。
- child 终态返回 `AgentRunResult`，不是只能返回自然语言文本。

**Red**

- `childCannotUseToolOutsideCapabilitySet`
- `childBudgetCountsAgainstParent`
- `childDeadlineCannotExceedParent`
- `rejectsSpawnBeyondDepthLimit`
- `rejectsSpawnBeyondConcurrencyLimit`
- `followUpTargetsExistingChildConversation`
- `cancelPropagatesToChildLlmAndTools`
- `terminalPayloadSurvivesSubAgentBoundary`

**DoD**

- `SubAgentTool` 退化为 `SubAgentRuntime` 的普通 tool adapter。
- diagnosis/coding 只提供 `AgentSpec` 和领域 payload 映射，不把工作流下沉 kernel。
- javadoc 和工具描述领域中立。
- 本任务不实现 peer 黑板、自动任务拆分或分布式调度。

**完成后的领域建模复核**

- commands：`spawn`、`followUp`、`cancel`；事实流为 child session 建立 → 独立 run segment 启动 → result/stop → 可选 follow-up，旧 conversation 不被复制或改写。
- `AgentSpec` 是静态 VO；`SubAgentHandle` 是串行 child session 生命周期边界；`SubAgentExecutionScope` 是显式 depth/quota 一致性边界；`AgentBudgetState` 是 child-local + ancestor-total 的分层账本。
- 变化点已分别收敛到 `LlmClientSelector`（model tier）、`ToolCapabilitySet`（能力）、`AgentRunLimits`（时间）、`SubAgentLimits`（depth/concurrency）和 terminal spec（结构化退出）。
- 建模评分从实施前的 **7/15** 提升到 **14/15**：聚合边界 3、变化收敛 3、不变量守护 3、行为一致 3、下一轮演进 2；剩余 1 分是 parent/child 生命周期关联尚未进入 typed interceptor/event schema，归 #48，不在 #47 偷改 `RunEvent` v1。

**blockedBy**：#40、#42、#43、#44、#46。

---

### #48 [Kernel-TDD, P2] typed `AgentInterceptor` 生命周期扩展

**状态**：已完成（2026-07-29）；Red/Green/Refactor 三提交交付，实施状态以 `TASKLIST.md` 为准。

**Goal**

在不开放任意脚本执行的前提下，为宿主提供可测试、可组合、失败语义明确的 in-process 生命周期扩展。

**当前问题**

`AgentEventListener` 只能观察 text/tool/usage 等事件，不能：

- 在 tool 执行前基于宿主策略阻断或改为 ASK；
- 在 compact 前后附加审计/脱敏；
- 在 run 停止前验证 terminal payload；
- 在 subagent spawn/stop 时建立关联；
- 对 prompt/context 做结构化脱敏。

直接让 listener 抛异常并不安全，因为当前异常可能使 tool batch 无法 settle。

**建议合同**

- `beforeLlmCall`
- `afterLlmCall`
- `beforeToolDispatch`
- `afterToolSettled`
- `beforeCompaction`
- `afterCompaction`
- `beforeRunStop`
- `onSubAgentSpawned/onSubAgentStopped`

pre-hook 返回 typed decision，如 `Continue`、`Deny(reason)`、`ReplaceContext`；不以任意 exception 作为正常控制流。

**关键不变量**

- `PermissionPolicy` 仍是独立安全决策，不被 hook 替代。
- observer 失败默认隔离；blocking interceptor 失败必须映射为明确 result/stop reason。
- interceptor 顺序固定且可观测；同优先级不得依赖集合迭代偶然顺序。
- tool-use 一旦进入 conversation，任何 interceptor 结果仍必须满足 #41 settle。

**Red**

- `preToolDenialProducesSettledDeniedResult`
- `observerFailureDoesNotFailRun`
- `blockingInterceptorFailureHasExplicitStopReason`
- `interceptorsRunInDeclaredOrder`
- `terminalValidationCanRejectBeforeStopWithoutLosingPairing`
- `subAgentLifecycleCarriesParentAndChildRunIds`

**DoD**

- Java typed SPI 可满足宿主审计/策略需求。
- 不加入 Claude Code 风格的任意 shell hook、脚本自动发现或插件系统。
- 核心 executor 只依赖 domain/application port，不依赖具体宿主。

**完成后的领域建模复核**

- commands：LLM/context/tool/run-stop 的 pre-hook 返回各自 sealed decision；events：LLM complete、tool settled、compaction installed 与 child segment spawned/stopped 使用不可变 typed event。正常拒绝与 callback failure 不再共享异常通道。
- 一致性边界仍是单个 run、`Conversation` tool batch 和单个 `ToolInvocation`；`AgentInterceptors` 是无 run 状态的声明有序策略链。并行 invocation 可并发回调，但各 invocation 内顺序稳定。
- `ReplaceContext` 只替换一次 provider request projection；tool blocking failure 以专用 result settle，整批 append 后才停止；terminal stop 拒绝清空 payload，但保留已配对 result。
- `PermissionPolicy`、workspace boundary、secret scope 与 output policy 仍独立且必经；interceptor 只能拒绝/替换自身合同允许的数据，不能授予权限或放大 child 能力。
- #47 的领域建模评分由 **14/15** 提升到 **15/15**：聚合边界 3、变化收敛 3、不变量守护 3、行为一致 3、下一轮演进 3。parent/child lifecycle correlation 已进入 typed event，同时有意保持 `RunEvent` v1 不变。

**blockedBy**：#41、#42、#46。

---

### #49 [Kernel-Infra/TDD, P2] MCP client、工具适配与生命周期

**Goal**

把 MCP server 暴露的工具安全地适配为 kernel `Tool`，支持 server lifecycle、命名空间、timeout、认证和动态 tool catalog。

**当前问题与证据**

- kernel POM 已依赖 `langchain4j-mcp`，但 [`infrastructure/mcp/package-info.java`](../agentkit-kernel/src/main/java/com/anthropic/agentkit/infrastructure/mcp/package-info.java) 是该 package 唯一生产文件。
- `ToolRegistry.specs()` 当前会把全部 schema 注入每次 LLM 请求；MCP 工具数量变大后会直接消耗 context window。
- 没有 server connection state、stdio/http transport、namespace collision、auth、timeout、tool annotations 或 refresh policy。

**范围**

- `McpToolAdapter`：name/description/schema/call/result/status 映射。
- `McpServerManager`：stdio 与 HTTP transport 的启动、健康、关闭和重连。
- namespace：至少 `<server>.<tool>`，拒绝与本地工具静默冲突。
- auth 只通过 `SecretProvider`，不得把 token 注入模型上下文或普通日志。
- MCP 的 read-only/destructive/idempotent annotation 映射为 permission policy 输入，但 annotation 不能绕过本地 deny。
- timeout/cancel 使用 #44；output 必经 #45；事件写入 #46。
- 支持 catalog refresh 和 deferred discovery；不要求每轮注入全部 schema。

**Red/测试矩阵**

- 本地 fake stdio server 的 discover/call/close。
- HTTP server timeout/cancel/reconnect。
- 重名工具 namespace 冲突。
- malformed schema/result 转成 settled error。
- destructive MCP tool 必须 ASK/DENY。
- secret 不进入 prompt、event payload 和日志。
- catalog refresh 原子替换，不影响 in-flight invocation。
- 大 catalog 只暴露被选中的 schema。

**DoD**

- MCP 工具与本地工具遵循同一 tool batch、permission、output、event 不变量。
- server 关闭不会泄漏进程/线程。
- 至少一个 fake stdio 集成测试和一个 HTTP 合同测试，无需真实外部 MCP 服务。
- 不在 kernel 内硬编码某个业务 MCP server。

**blockedBy**：#43、#44、#45、#46；建议在 #48 后实施。

---

### #50 [Kernel-TDD, P2] Background `TaskHandle` + output artifact store

**Goal**

让长时间 Bash/外部工具可以后台运行、增量读取、监控和停止；大输出保存在受控 artifact store，模型上下文只接收 preview/reference。

**当前问题**

- `BashTool` 同步等待进程结束并一次性返回完整 stdout/stderr。
- 没有 background task ID、status、partial output cursor、stop、ownership 或过期清理。
- 大输出依赖字符串截断，截断后的完整内容没有统一可引用存储。

**建议模型**

```java
interface TaskHandle {
    TaskId id();
    TaskState state();
    OutputChunk readSince(OutputCursor cursor);
    CompletionStage<ToolResult> completion();
    boolean cancel();
}
```

状态至少 `STARTING/RUNNING/COMPLETED/FAILED/CANCELLED/TIMED_OUT`。TaskId 必须绑定 RunId/WorkspaceId；另一个 workspace 不能读取或停止它。

**Red**

- `backgroundTaskReturnsHandleBeforeProcessCompletes`
- `readsOutputIncrementallyWithoutDuplication`
- `cannotReadTaskFromAnotherWorkspace`
- `cancelTerminatesProcessTree`
- `completedTaskSettlesOriginalInvocationExactlyOnce`
- `largeOutputStoresArtifactAndReturnsBoundedPreview`
- `expiredArtifactCannotEscapeWorkspacePolicy`

**边界决策**

后台任务会挑战“一个 tool-use 必须在下一轮 LLM 前 settle”的协议。推荐把“启动后台任务”本身立即 settle 为包含 TaskId 的成功结果；后续 status/read/stop 是新的工具调用。不要让一个原始 tool-use 跨多个用户回合长期 pending。

**DoD**

- 后台启动与查询均遵循普通工具配对协议。
- process tree 能被取消并回收；close/run stop 有清理策略。
- artifact store 有大小、TTL、scope 和脱敏策略。
- 不实现分布式队列或跨机器 worker。

**blockedBy**：#41、#43、#44、#45、#46。

---

### #51 [Kernel-TDD, P2] `WAITING_FOR_INPUT` / `WAITING_FOR_APPROVAL` 可恢复运行态

**Goal**

把向用户提问、计划批准和工具 ASK 从同步 UI 回调提升为可持久化的 run suspension，使 CLI/Web 宿主都能暂停后恢复。

**当前问题**

- `PermissionMode.PLAN` 当前只是“只读 allow、写工具 deny”，没有 plan artifact、批准状态或恢复 token。
- `InteractivePrompter` 假设同进程同步交互，不适合 Web/异步宿主。
- kernel 没有通用 AskUserQuestion/request-input primitive；agent 只能用自然语言结束。

**建议模型**

```java
sealed interface RunSuspension {
    record WaitingForInput(InputRequest request) implements RunSuspension {}
    record WaitingForApproval(ApprovalRequest request) implements RunSuspension {}
}
```

`AgentRunResult` 返回 suspension + resume token。宿主提交 answer/decision 后建立新的 run segment，沿用 SessionId，生成新的 RunId 或明确 segment ID。

**关键不变量**

- pending approval 持久化后才能向宿主返回 waiting。
- resume token 单次消费且绑定 session/workspace/request。
- 用户答案是新的 user event，不改写旧 event。
- coding plan 仍是 coding domain VO；kernel 只认识通用 approval/input envelope。

**Red**

- `askPermissionSuspendsWithoutExecutingTool`
- `approvedResumeExecutesOriginalInvocationOnce`
- `deniedResumeSettlesOriginalInvocationAsDenied`
- `resumeTokenCannotBeReusedOrCrossWorkspace`
- `inputAnswerIsAppendedAsNewEvent`

**DoD**

- CLI 和 Web 可以使用同一 suspension contract。
- 不需要让 application thread 在 prompt 上阻塞。
- 不把 coding/diagnosis 的业务审批规则下沉 kernel。

**blockedBy**：#42、#43、#46。

---

### #52 [Kernel-TDD, P3] 高级 session fork / checkpoint / rewind

**Goal**

在 append-only run event 基础上支持会话分支、历史 rewind 和 kernel 文件编辑 checkpoint；明确区分消息回退与外部副作用回滚。

**为什么不是高优先级**

`#46` 已解决“安全恢复、不重复执行”的底线。Claude Code 的 checkpoint/rewind 和 Codex 的 session/worktree 能力更进一步，但通用回滚 Bash/MCP/远端系统并不可能由 kernel 自动保证。应先有真实交互需求再扩展。

**范围**

- `SessionBranchId`、parent event sequence、fork metadata。
- rewind 创建新 branch，不物理删除原 event。
- kernel `FileWrite/FileEdit` 可通过 `FileCheckpointProvider` 保存变更前内容/metadata。
- Bash、MCP、数据库等标记为 non-reversible；UI 必须展示 residual side effects。
- worktree 建立/合并策略属于 coding/platform，不进入 kernel。

**Red**

- `forkReferencesImmutableParentSequence`
- `rewindCreatesBranchWithoutDeletingHistory`
- `fileCheckpointRestoresKernelManagedEdit`
- `rewindReportsNonReversibleSideEffects`
- `branchCannotCrossWorkspaceBoundary`

**DoD**

- 用户可以区分“仅恢复 conversation”“恢复 kernel 文件编辑”“外部副作用无法撤销”。
- event history 保持 append-only，可审计。
- 不承诺通用 transaction/rollback。

**blockedBy**：#43、#46；若需后台进程状态，还 blockedBy #50。

---

### #53 [Kernel-TDD, P3] provider-neutral `ModelPolicy` / `RetryPolicy`

**Goal**

把模型档位选择、瞬态错误重试、rate-limit backoff 和可选 fallback 收敛成策略；保证重试不会重复工具副作用或绕过预算。

**当前问题**

- provider 差异已由 factory 收敛，但 retry/fallback/模型档位仍没有通用 runtime 模型。
- 子 Agent 只能复用同一个 `LlmClient`，不能以 `ModelTier` 选择成本/能力。
- 如果在 executor 外盲目重试整个 run，可能重放已经完成的工具。

**关键规则**

- 只允许在**尚未接受完整 assistant turn**时重试 LLM call。
- 工具调用绝不因 provider retry 自动重放。
- retry 消耗计入 run budget/deadline。
- fallback 必须记录实际 provider/model，prompt/tool schema 兼容性由 adapter 验证。
- context overflow 由 #45 专门处理，不和通用瞬态重试混成无限循环。

**Red**

- `retriesTransientProviderFailureBeforeAssistantTurn`
- `doesNotRetryNonTransientAuthenticationFailure`
- `retryConsumesBudgetAndHonorsDeadline`
- `neverReplaysSettledToolAfterProviderFailure`
- `recordsActualFallbackModelInUsage`

**DoD**

- policy 独立于 Anthropic/OpenAI SDK 类型。
- 默认 retry 次数有限且有 jitter/backoff 可测试时钟。
- 没有真实需求时 fallback 默认关闭。

**blockedBy**：#42、#44、#46；`ModelTier` 与 #47 协同。

---

### #54 [Platform-Infra/TDD, P3] `AgentManifest` + coding 正式入口

**Goal**

当多个 agent 需要被同一宿主发现和派发时，用自描述 manifest 取代组合根对具体模块的硬编码，并为 coding agent 提供正式 engine/builder 入口。

**为什么现在已满足触发条件**

- `AGENTS.md` 原定“第二个 agent 真要插入时再上”。当前仓库已有 `agentkit-agent-diagnosis` 和 `agentkit-agent-coding` 两个平级 agent 包。
- coding 目前只有 [`CodingPipeline.java`](../agentkit-agent-coding/src/main/java/com/anthropic/agentkit/application/coding/CodingPipeline.java)，没有与 diagnosis `DiagnoseEngine/Builder` 对等的正式入口。
- `DESIGN.md §16.7` 第 616 行仍写“等第二个 agent 真要插入”，文档条件已经过期。

**建议 manifest**

```java
record AgentManifest(
        AgentId id,
        String description,
        AgentEntryPoint entryPoint,
        Set<ConfigKey> requiredConfigKeys,
        CapabilityDescriptor capabilities) {}
```

**边界**

- Manifest/registry 属于 platform/composition，不应让 kernel 反向依赖 diagnosis/coding。
- agent 模块提供 manifest；平台加载并派发。
- 先用显式注册，除非有真实插件需求，不引入反射 classpath scanning、Spring/DI 或插件子系统。

**Red/DoD**

- duplicate agent ID 启动失败。
- 缺 required config 时在运行前给出明确错误。
- capability 与实际 `AgentSpec`/tool boundary 一致。
- coding 有稳定的 `CodingEngine`/builder/entry point，宿主无需直接拼 planner/patcher/reviewer。
- ArchUnit 保证 kernel 不依赖任何 agent 包，agent 包之间也不互相依赖。

**blockedBy**：#42、#47；至少有一个真实统一派发入口。

---

### #55 [CLI/Platform-TDD, P3] 组合根、slash command 与 SIGINT 接线清理

**Goal**

修复当前 CLI 宣称能力和实际 wiring 的漂移，但保持 UI 逻辑在 interfaces/cli，不把它包装成 kernel 功能。

**当前问题**

- `AgentKitApplication` 生产代码只注册 Help/Clear，help 文本却宣称 `/resume`。
- `ClearCommand` 只返回字符串，没有清空 active conversation。
- `SigintHandler` 被创建，但生产代码没有把真实 SIGINT 完整接到 `onSigint()`；token cancel 后下一轮也需要创建新 token。
- `AgentManifest` 落地后，CLI 仍需要明确的 agent 选择与配置错误展示。

**范围**

- slash command 声明与实际注册同源，避免 help 漂移。
- `/clear` 通过用例端口清空/新建 active conversation。
- `/resume` 真正使用 #46 的 projection/recovery，并显示 unknown in-flight invocation。
- SIGINT 第一次取消 active run，空闲时不污染下一轮；是否二次 Ctrl-C 退出由 CLI policy 决定。
- CLI 通过 #54 registry 选择 agent，不在 kernel 加 UI 命令。

**DoD**

- help 中展示的命令全部可用，不存在生产未注册命令。
- clear/resume/SIGINT 有端到端 CLI 测试。
- 每次新 run 使用新 `AgentRunContext`/CancellationToken。
- 不增加 Ink/React UI、IDE bridge 或插件系统。

**blockedBy**：#40、#46、#54。

---

### #56 [Kernel-Infra/TDD, P3] `RunEventStore` 索引、retention 与多进程 writer fencing

**Goal**

在单个 run 事件量或并发宿主规模真实增长后，为 append-only 事实日志增加有界查找成本、明确保留策略和跨实例 writer 排他，同时保持 `#46` 的序列、scope、崩溃尾记录与不重放语义。

**当前边界**

- `FileRunEventStore.append` 为验证下一 sequence 会读取并解码该 run 的完整日志；不会重写旧前缀，但长期 run 的累计读成本为 O(n²)。
- `synchronized` 只保护一个 store 实例；当前 L0 组合根是单进程、单实例 writer，尚未承诺多个 JVM/worker 同时写同一 RunId。
- 每个 run 一个 JSONL 文件，没有 size/time retention、rotation、冷归档或可重建 tail index。
- load 的严格全量校验适合恢复与审计，但不等于面向大量事件的分页查询接口。

**建议范围**

- 可重建 tail index：至少记录最后 sequence、文件长度和已验证 schema/scope；index 损坏时从 JSONL 事实重建，不能反客为主。
- writer fencing：同一 RunId 同时只允许一个合法 writer；文件锁或宿主 lease 的失败语义必须明确，不允许两个 writer 都自认为成功。
- retention/rotation：必须保留审计与恢复所需边界；删除/归档是显式 policy，不得在 append 路径静默丢事件。
- paged read/tail API 与完整 `load` 分离；`RunEventResumer` 仍得到一条连续、已校验的事实流。

**Red/DoD**

- `appendCostDoesNotGrowWithEntireValidatedPrefix`
- `rejectsConcurrentWriterForSameRun`
- `rebuildsTailIndexAfterCrashOrDeletion`
- `rotationPreservesContiguousProjection`
- `retentionNeverDeletesActiveOrUnsettledRun`
- `corruptIndexCannotHideCorruptEventLog`
- Windows/Linux 文件锁或 lease 合同均有平台测试；不把数据库或分布式队列提前下沉 kernel。

**blockedBy**：#46（已满足）；只有长 run 事件量、多个 JVM writer 或磁盘保留需求出现时立项。

## 6. 暂不建议立项的能力

下列能力在 Claude Code/Codex 产品中可能存在，但当前项目没有足够需求或与既定边界冲突：

- 多模态图片/截图输入；
- IDE bridge、远程会话和插件市场；
- 任意 shell/script hooks；
- Skill 自动执行脚本或自动开启 Bash；
- peer-to-peer 黑板式多 Agent 协作；
- kernel 内置 Planner/Coder/Reviewer 流水线；
- 多租户控制面、数据库、队列、容器调度；
- 对 Bash、MCP、远端数据库承诺通用 checkpoint rollback；
- 反射扫描 agent/plugin 或引入 Spring/Guice。

这些能力不是“漏做”，而是当前 `DESIGN.md`/`AGENTS.md` 已明确排除，或必须等到 L2 多用户拐点再重新设计。

## 7. 立项触发条件

| 任务 | 合理触发条件 |
|---|---|
| #47 通用子 Agent | coding/diagnosis 至少一个需要 follow-up、独立 model tier 或 background child |
| #48 Interceptor | agent-web/CLI 出现第二个 lifecycle 干预需求，listener 无法表达 |
| #49 MCP | 有明确 MCP server、认证方式、transport 和工具规模样本 |
| #50 Background | 有真实命令超过同步 timeout，且用户需要继续交互/轮询 |
| #51 Waiting states | Web 宿主需要异步 ASK，或 coding plan 需要跨请求批准 |
| #52 Checkpoint | 用户明确需要 rewind/fork，且接受非文件副作用不可回滚 |
| #53 Retry/Model policy | provider rate-limit/瞬态错误达到可观测频率，或 child model tier 有成本需求 |
| #54 Manifest | diagnosis/coding 需要由同一入口真实派发；当前已接近满足 |
| #55 CLI cleanup | CLI 重新成为交付面，或用于验证统一 runtime |
| #56 Event scaling | 单 run 日志达到可测性能瓶颈、同一 RunId 出现多进程 writer，或宿主提出 retention/归档要求 |

## 8. 与高优先级任务的边界复核

后续任务进入开发前，应逐项回答：

1. 是否使用同一个 `AgentRunContext`，没有读取全局 cwd/secret？
2. 新增的每个 tool-use 是否在 deny/error/cancel/timeout 时仍 settle？
3. 是否返回/传播 `AgentRunResult` 和明确 stop reason？
4. 是否受 workspace、permission、deadline、budget、output policy 约束？
5. 是否写入 append-only run event，并有 safe resume 语义？
6. 是否把领域工作流留在 agent 包，而不是塞进 kernel executor？

任一答案为“否”，应先修对应高优先级任务，不能在新功能内部做一套局部补丁。

## 9. 对照资料

Claude Code：

- [Subagents](https://code.claude.com/docs/en/sub-agents)
- [Hooks](https://code.claude.com/docs/en/hooks)
- [MCP](https://code.claude.com/docs/en/mcp)
- [Sessions](https://code.claude.com/docs/en/sessions)
- [Checkpointing](https://code.claude.com/docs/en/checkpointing)
- [Tools reference](https://code.claude.com/docs/en/tools-reference)

Codex：

- [Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents)
- [Hooks](https://learn.chatgpt.com/docs/hooks)
- [MCP](https://learn.chatgpt.com/docs/extend/mcp)
- [Approvals and security](https://learn.chatgpt.com/docs/agent-approvals-security)
- [Git worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees)
- [Developer commands](https://learn.chatgpt.com/docs/developer-commands?surface=cli)

对照只用于提炼 runtime primitive 和流程不变量；产品 UI、插件生态和托管控制面不作为 kernel 对齐目标。
