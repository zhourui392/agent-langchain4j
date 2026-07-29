# AgentKit Kernel：非高优先级与后续 Agent Runtime 任务

> 状态：`#47–#52` 已完成，下一项为 `#53`；随后按 `#54 → #55` 继续交付，`#56` 保持条件触发候选
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

建议交付顺序：`#47 → #48 → #49 → #50/#51 → #52/#53 → #54/#55`；当前 `#47–#52` 已完成，下一项为 `#53`。`#56` 独立按日志规模或多 writer 需求触发，不为“对齐产品功能表”提前实现没有消费方的扩展。

## 4. 变化点地图

| 变化来源 | 当前分散位置 | 推荐收敛点 | 对应任务 |
|---|---|---|---|
| 角色 prompt、工具、模型、预算 | `StructuredAgent` 构造参数、`SubAgentTool` 固定逻辑、各 agent 包 | `AgentSpec` + `SubAgentRuntime` | #47 |
| 运行前后干预 | `AgentEventListener`、permission、各入口手工调用 | typed `AgentInterceptor` | #48 |
| 外部工具服务器 | `McpToolAdapter`、scope-keyed server/session 与 context-aware `ToolCatalog` 已落地 | `McpProtocolMapper` + `McpCatalogPolicy` + server lifecycle | #49（已完成） |
| 长命令和大输出 | scoped `TaskHandle`、增量 cursor、进程树回收、受治理 artifact 已落地 | `BackgroundTaskLauncher` + output projection + `ArtifactStore` | #50（已完成） |
| 用户问题和计划批准 | typed suspension、完整 permission preflight、单次 token claim 与 CLI host adapter 已落地 | `RunSuspension` + `RunSuspensionStore` + `ResumeCommand` | #51（已完成） |
| fork/rewind/checkpoint | immutable parent pointer、append-only branch journal、typed side effect 与文件补偿已落地 | `SessionBranchService` + `FileCheckpointProvider` | #52（已完成） |
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

**状态**：已完成（2026-07-29）；Red/Green/Refactor 三提交交付，实施状态以 `TASKLIST.md` 为准。

**Goal**

把 MCP server 暴露的工具安全地适配为 kernel `Tool`，支持 server lifecycle、命名空间、timeout、认证和动态 tool catalog。

**实施前问题与证据**

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

**实施前领域建模审计**

- commands：`open scope session`、`refresh catalog`、`expose selected tools`、`invoke tool`、`close scope/all sessions`；events/facts：session ready/failed、catalog snapshot replaced、invocation settled、session invalidated/closed。连接失败后的当前 invocation 只 settle 为失败，reconnect 只影响后续 command，禁止自动重放可能有副作用的调用。
- ubiquitous language：`McpServerConfig` 只保存 transport 与 secret **引用**；`McpSession` 是一个 `SecretScope` 内的连接生命周期；`McpToolDescriptor` 是远端声明；`ToolCatalogSnapshot` 是一次不可变、可原子替换的本地投影；`McpToolAdapter` 是 kernel `Tool`，不是旁路 executor。
- 聚合/一致性边界：单个 `(SecretScope, serverId)` session 独占连接与 catalog generation；refresh 先完整校验新目录再单次替换，旧 adapter 可完成已开始的 invocation；跨 server 合并与本地工具重名由 `ToolRegistry` 在同一 resolution snapshot 中拒绝。
- 核心不变量：server/tool 必须形成 `<server>.<tool>`；secret value 不进入 schema、prompt、event、普通日志或异常；annotation 只能收紧/提示本地 permission，不能授予权限；每个远端调用继续经过 interceptor、permission、run timeout/cancel、output policy、event recorder 和 ordered batch settle；malformed schema 不得部分安装，malformed result 必须形成 settled error；scope 不匹配不得复用已认证 session。
- 变化点：stdio/HTTP 收敛到 transport spec/factory；认证目标名→secret name 收敛到显式 binding；远端 schema/result 收敛到 protocol mapper；eager/deferred exposure 收敛到 catalog policy；连接恢复收敛到 session invalidation/reopen，调用重试策略不混入 transport。
- 反模式警戒：不让 `McpServerManager` 持有一个无 scope 的全局认证 client；不在 `AgentExecutor` 写 transport 分支；不把整个大 catalog 每轮注入；不读取 `System.getenv`；不依赖 MCP `readOnlyHint` 绕过 `PermissionPolicy`。
- 实施前评分 **7/15**：聚合边界 2、变化收敛 2、不变量守护 1、行为一致 1、下一轮演进 1。目标是在 Refactor 后由 typed scope、atomic snapshot、normal-tool path 和 transport strategy 提升到至少 14/15。

**完成后领域建模复核**

- ubiquitous language 已由实现物化：`McpServerConfig` 只保存 transport 与 secret name binding；`McpSession` 代表一个 scope 内连接；`McpCatalogGeneration` 是完整校验后一次发布的不可变 generation；`McpToolAdapter` 是唯一进入 kernel 普通工具路径的远端工具形态。
- 聚合边界固定为单个 `(SecretScope, serverId)` 的 `ServerState`：session、catalog generation、retired session 与 invalidation 在同一 lifecycle lock 下转换；跨 server 合并只由 context-aware `ToolRegistry` 负责，不共享认证 client。
- catalog 不变量集中在 `McpCatalogPolicy`：远端 schema/name 全量验证后才原子发布；声明/发现顺序稳定；大 catalog 只暴露 discovery tool 与显式选择的 schema；refresh 不打断持有旧 adapter 的调用。
- transport/protocol 变化点集中在 `McpSessionFactory`、`McpTransportSpec` 与 `McpProtocolMapper`；认证只经 `ExecutionContext.secret` 解析，远端结果在进入普通工具链前做精确 secret redaction。
- connection failure 只 invalidate 当前 session 并把当前 invocation settle 为 ERROR；后续调用才 reopen，禁止自动 replay。permission、interceptor、deadline/cancel、output policy、event recorder 与 ordered batch settle 均复用 `AgentExecutor` 的单一路径。
- 完成后评分 **15/15**：聚合边界 3、变化收敛 3、不变量守护 3、行为一致 3、下一轮演进 3。stdio/HTTP、新认证 binding、catalog exposure 与 wire payload 变化均已有独立收敛点，且未把业务 server 或领域工作流下沉 kernel。

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

**状态**：已完成（2026-07-29）；Red/Green/Refactor 三提交交付，实施状态以 `TASKLIST.md` 为准。

**Goal**

让长时间 Bash/外部工具可以后台运行、增量读取、监控和停止；大输出保存在受控 artifact store，模型上下文只接收 preview/reference。

**实施前问题（已关闭）**

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

**实施前领域建模审计**

- commands：`start background task`、`read since cursor`、`inspect status`、`stop task`、`close run/all tasks`、`write/read/expire artifact`；events/facts：task accepted、output appended、task completed/failed/cancelled/timed-out、artifact stored/expired。启动命令成功即代表原 tool-use 已 settle，不等待后台完成。
- ubiquitous language：`TaskId` 是任务身份；`TaskScope` 是 `(RunId, WorkspaceId)` ownership；`TaskHandle` 是单个任务聚合的行为边界；`OutputCursor` 是 append-only 输出位置；`TaskSnapshot` 是状态投影；`ArtifactReference` 是受控引用而非文件路径。
- 聚合/一致性边界：单个 `TaskHandle` 独占 state、completion 与输出 cursor；`BackgroundTaskService` 只注册 scope→handle 并校验 ownership；artifact 写入是 completion 后的独立外部状态操作，只有写入成功后 snapshot 才能发布 reference。
- 核心不变量：任务状态只从 STARTING/RUNNING 进入一个 terminal state；cursor 单调且相同 cursor 重读稳定；另一个 run/workspace 看不到也不能停止任务；cancel 必须回收 root 与 descendants；后台 completion 不能再次 append 原 invocation；artifact 写前脱敏，并受 size/TTL/scope 约束。
- 变化点：process 启动/回收收敛到 `BackgroundTaskLauncher`；preview/reference 收敛到 `BackgroundTaskPolicy`；持久介质收敛到 `ArtifactStore`；内容治理收敛到 `ArtifactContentPolicy`；run-stop 回收复用 typed interceptor，不向 `AgentExecutor` 写 process 分支。
- 反模式警戒：不让原始 tool-use 跨用户回合 pending；不把 `Process`/PID 放进 domain；不以可猜文件路径充当 artifact URI；不使用进程 CWD；不让另一个 scope 通过 TaskId 探测任务存在；不实现队列、scheduler 或跨机器 worker。
- 实施前评分 **6/15**：聚合边界 1、变化收敛 2、不变量守护 1、行为一致 1、下一轮演进 1。已有同步 `ProcessRunner`、cancellation 与 output metadata 可复用，但尚无任务聚合、scope registry、cursor 或 artifact port；Refactor 后目标至少 14/15。

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

**完成后领域建模审计**

- Summary：单个 `(TaskId, TaskScope)` 任务成为明确一致性边界；启动命令即时 settle，后台 completion 只更新 task projection，不再接触原 Conversation。process、artifact 与 run-stop 分别经 port、policy 和 typed interceptor 收敛，未向 `AgentExecutor` 添加后台进程分支。
- Domain Concept Map：`TaskHandle` 是任务聚合行为边界，`TaskId`/`TaskScope`/`OutputCursor`/`ArtifactReference` 是 VO，`TaskSnapshot`/`TaskStopResult` 是只读命令结果；`BackgroundTaskService` 是 scope registry 与用例协调器，`BackgroundTaskLauncher`/`ArtifactStore` 是外部状态 port。
- Aggregate Boundary：state、completion 和 append-only output 由一个 handle 独占；registry 只按显式 run/workspace scope 暴露 handle。artifact 是 completion 后的独立一致性边缘，写失败只降级为明确 omission，不把已成功任务伪装成失败，也不发布虚假 reference。
- Invariants：`TaskState.transitionTo` 与 terminal claim 保证终态不回退且 completion 恰好一次；同 cursor 重读稳定、next cursor 单调；异 scope 统一表现为 unknown；scope close 先封口再回收，不能并发遗留新任务；cancel/timeout 只允许一个终态获胜并回收所有已观察进程；artifact 写前治理且受 scope/size/TTL/owner-only 权限约束。
- Variation Point Map：命令启动在 `BackgroundTaskLauncher`，process-tree 策略在 `ProcessTreeTerminator`，active/terminal projection 在 `BackgroundTaskOutputProjector`，preview/reference 在 `BackgroundTaskPolicy`，脱敏在 `ArtifactContentPolicy`，介质在 `ArtifactStore`，run-stop 清理在 `BackgroundTaskCleanupInterceptor`。普通工具若已截断，artifact policy 不会把残缺正文重新标成完整输出。
- Refactor Signals：`TaskStopResult` 取代固定 `CANCELLED` 投影；`ArtifactStoreException` 上移到 domain port 合同；默认 CLI 组合根显式管理 launcher/service 生命周期并注册四个 task tool。后续若出现跨 run 持续任务需求，应新建有 durable ownership 的任务模型，不能放宽当前 RunId scope。
- Review Questions：当前 L0 合同有意在 run stop 时回收任务；不支持 scheduler、跨机器 worker、跨 run 持续任务或通用进程沙箱。artifact 到期采用读时拒绝与 best-effort 删除，若规模触发集中清理需求，应另立 retention 任务。

| 评分维度 | 完成后 | 证据 |
|---|---:|---|
| 聚合边界是否清晰 | 3/3 | handle 独占任务状态/output/completion，service 只管 scope registry 与 projection |
| 变化是否被收敛 | 3/3 | launcher、terminator、content/preview policy、artifact port、cleanup interceptor 各有单一变化轴 |
| 不变量是否可被模型守护 | 3/3 | typed state transition、terminal CAS、scope ownership/close fence、cursor VO 与 artifact reference VO |
| 行为是否与模型一致 | 3/3 | start/status/read/stop 均为普通配对工具调用，stop 返回实际 terminal snapshot，completion 不回写 Conversation |
| 设计是否支持下一轮变化 | 3/3 | 显式 ExecutionContext/TaskScope 与 port 可替换介质/launcher，同时明确拒绝分布式与跨 run 语义漂移 |
| **总分** | **15/15** | 从实施前 6/15 提升，所有 DoD 均有定向测试与全仓门禁覆盖 |

**blockedBy**：#41、#43、#44、#45、#46、#47。

---

### #51 [Kernel-TDD, P2] `WAITING_FOR_INPUT` / `WAITING_FOR_APPROVAL` 可恢复运行态

**状态**：已完成（2026-07-29）；Red/Green/Refactor 三提交交付，实施状态以 `TASKLIST.md` 为准。

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

**实施前领域建模审计（2026-07-29）**

- Summary：同步 `InteractivePrompter` 把“等待用户”错误地建模成 application 调用栈中的短暂分支；真正的一致性边界应是可持久化的 suspension/request 与一次性 token claim。approval 与 input 共享暂停/恢复协议，但消息投影不同：approval 的 tool batch 在恢复前不能进入 `Conversation`，input answer 则必须成为新消息和新事实。
- Commands / Events：命令是 `RequestInput`、`RequestApproval`、`SuspendRun`、`Approve`、`Deny`、`SubmitInputAnswer`、`ClaimResumeToken`、`ResumeRunSegment`；事实是 `RunSuspended`、`ApprovalSubmitted`、`InputAnswered`、`ToolInvocationStarted/Settled`、`RunStopped`。token claim 必须先于任何恢复后的外部执行。
- Ubiquitous Language：`RunSuspension` 是一次已持久化、尚未回答的等待；`SuspensionId` 是公开审计标识；`ResumeToken` 是不可写入 event/prompt/log 的单次凭证；`ApprovalRequest` 保存完整原 tool batch 及当时的 permission plan；`InputRequest` 是领域无关问题 envelope；`ResumeCommand` 只表达批准、拒绝或答案，不携带领域计划/诊断概念。
- Domain Concept Map：聚合根为 pending `RunSuspension`；值对象包括 `SuspensionId`、`ResumeToken`、`SuspensionScope(sessionId, workspaceId, originatingRunId)`、`ApprovalRequest`、`InputRequest`、`InputAnswer` 与 `ApprovalDecision`；`RunSuspensionStore` 是原子 save/claim port；`AgentExecutor` 只编排新旧 run segment，不拥有 token 状态。
- Aggregate Boundary：suspension payload、scope、expected response kind 与 token 消费属于同一个强一致边界；`Conversation` 与 `RunEvent` 是 append-only 投影，不参与 token 事务。approval 首段只持久化 pending assistant batch，不能把未配对 tool-use append 到 Conversation；新 segment claim 成功后才投影原 assistant batch，并按原顺序 settle 全批。input 问题可以作为无 tool-use 的 assistant message 完成投影，答案在新 segment 追加为 user message/event。
- Invariants：pending save 成功后才能返回 waiting；原 run 与 resume run 必须同 session/workspace 且 RunId 不同；token 不可猜测、不可跨 scope/type、不可复用，错误 scope/type 不得误消费；permission batch 必须在任何工具执行前完整规划，存在 ASK 时全批暂停；批准不能覆盖原 policy DENY；拒绝时全批完整 settle 且零执行；批准后原 invocation 最多开始一次；claim 后执行中断不得 replay；恢复结果仍按原 `tool_use` 顺序 append；旧 event/message 永不改写；答案是新事实，不藏在旧 suspension 更新中。
- Variation Point Map：持久化介质收敛到 `RunSuspensionStore`；token 生成/摘要与原子 claim 收敛到 store adapter；CLI/Web 只适配 `RunSuspension`/`ResumeCommand`；approval/input envelope 由 sealed variant 隔离；批量权限差异收敛到 preflight plan；coding/diagnosis 的计划审批规则留各 agent application/domain，不进入 kernel。
- Refactor Signals：`PermissionService.check` 当前同时做 policy evaluation、同步 UI 和 cache，需拆出非阻塞 decision/preflight；`ParallelToolDispatcher` 当前在虚拟线程内逐个 ASK，需把 permission planning 移到并发执行之前；`AgentRunResult` 的 waiting stop reason 尚无 typed payload；`RunEventProjector` 当前会把未 settle batch误判为 interrupted，需显式认识 suspension/resume 事实。
- Review Questions：本阶段按“batch 内任一 ASK 则全批暂停”处理，以免先发生部分副作用；拒绝会安全地 settle 全批而不执行原本 ALLOW 项。跨进程 durable store 只保证 token claim 不重放，不承诺 claim 后进程崩溃时自动判断外部副作用结果；该状态只能审计/对账，不能用同 token 重试。

| 评分维度 | 实施前 | 证据与缺口 |
|---|---:|---|
| 聚合边界是否清晰 | 2/3 | 目标边界已确定，但尚无 suspension aggregate/store |
| 变化是否被收敛 | 1/3 | 同步 prompter、permission 与 dispatcher 仍耦合 |
| 不变量是否可被模型守护 | 1/3 | StopReason 已预留，token claim/配对尚无模型守护 |
| 行为是否与模型一致 | 1/3 | ASK 仍阻塞线程且可在并行 batch 中途发生 |
| 设计是否支持下一轮需求变化 | 1/3 | CLI/Web、approval/input 尚未共享 typed contract |
| **总分** | **6/15** | Red 将先固定 scope、claim、batch pairing 与 append-only 验收线 |

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

**完成后领域建模复核（2026-07-29）**

- Summary：`RunSuspension` 已成为 pending request/token 的强一致聚合，`AgentExecutor` 只编排首段与恢复段；CLI 只是 `RunSuspension`/`ResumeCommand` 的宿主 adapter。同步 `InteractivePrompter` 保留为无 suspension store 的兼容路径，但启用 resumable store 后，完整 batch 只评估一次 permission snapshot，dispatcher 不会二次 ASK。
- Domain Concept Map：sealed `WaitingForApproval`/`WaitingForInput`、`SuspensionScope`、`ApprovalRequest`/`InputRequest`、`ResumeToken`/`ResumeScope` 与 typed command 已落地；`AgentRunResult` 一等返回 suspension/token；`FileRunSuspensionStore` 实现 durable save 与原子 claim。
- Aggregate Boundary：approval 首段持久化完整 assistant batch 但不 append Conversation；claim 后的新 RunId 先追加 `ApprovalSubmitted`，再把原 batch 与有序 results 投影。input 问题可完成普通 assistant 投影，答案由新段追加 `InputAnswered` 与 `UserMessage`。event recovery 能区分合法 suspension 与 interrupted tool batch。
- Invariants：token 使用 256-bit 随机值，文件名只保存 SHA-256 digest，raw token 不进 payload/event/log/异常；claim 的 `.pending`→`.claimed` 原子 move 在两个 store 实例并发下恰好一胜；错 session/workspace/kind/origin 不消费；run-stop interceptor 在 durable save 前校验，拒绝时不发布 token；批准沿原 permission plan 执行一次，原 DENY 不能被覆盖；拒绝全批配对且零执行。
- Variation Point Map：durable 介质只经 `RunSuspensionStore`，CLI/Web 只需实现 host prompt/response adapter，permission 变化收敛到 `ToolPermissionPlan`，run event codec 与 projection 显式认识 suspension facts。coding plan、diagnosis hypothesis 等 payload 继续留各 agent 包。
- Refactor Signals：当前 file store 是 L0 本机 durable claim，不提供 token 找回、TTL/retention 或分布式 lease；claim 成功后的外部副作用若进程崩溃仍只允许审计/对账，禁止 replay。后续若要让 sub-agent 自身跨请求等待，应把同一个 store/host resume contract 显式传入 sub-agent runtime，不能恢复同步 prompt。

| 评分维度 | 完成后 | 证据 |
|---|---:|---|
| 聚合边界是否清晰 | 3/3 | suspension/scope/token claim 强一致，Conversation/RunEvent 仅 append-only 投影 |
| 变化是否被收敛 | 3/3 | store、permission plan、host adapter、event codec/projection 各守单一变化轴 |
| 不变量是否可被模型守护 | 3/3 | typed scope/kind、原子 claim、single-use token、完整 plan 与 batch pairing |
| 行为是否与模型一致 | 3/3 | 首段不阻塞、不留悬空 tool-use；恢复段新 RunId，approve/deny/input 均为新事实 |
| 设计是否支持下一轮需求变化 | 3/3 | CLI 已消费通用 contract，Web 可复用；领域审批 payload 未下沉 kernel |
| **总分** | **15/15** | 五个 Red 场景、并发 store、codec/recovery 与 CLI adapter 均有定向测试 |

**blockedBy**：#42、#43、#46。

---

### #52 [Kernel-TDD, P3] 高级 session fork / checkpoint / rewind

**状态**：已完成（2026-07-29）；Red/Green/Refactor 三提交交付，实施状态以 `TASKLIST.md` 为准。

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

**实施前领域建模审计（2026-07-29）**

- Summary：rewind 不是对旧 session/event log 做原地截断，而是在不可变 `RunEventPointer` 上创建新 branch；conversation 投影、kernel 文件恢复与外部残余副作用是三类不同结果，必须分别建模和展示。强一致边界是 branch metadata/event stream，run event 与文件 checkpoint 分别作为不可变事实引用和可补偿资源。
- Commands / Events：命令是 `CreateRootBranch`、`ForkBranch`、`RewindBranch`、`CaptureFileCheckpoint`、`RestoreCheckpoint`；事实是 `BranchCreated`、`ToolSideEffectObserved(CheckpointedFile|NonReversible)` 与既有 `RunEvent`。fork/rewind 只追加新 branch 事实，不发出删除或重写历史的命令。
- Ubiquitous Language：`RunEventPointer(runId, sequence)` 是不可变事实坐标；`BranchPoint(branchId, event)` 是父分支引用；`SessionBranch` 是带 session/workspace scope、origin、parent point 与 head 的聚合投影；`CheckpointId` 引用 kernel 在文件写入前保存的快照；`ResidualSideEffect` 是 rewind 后仍存在、kernel 不声称已撤销的外部影响。
- Domain Concept Map：`SessionBranch` 是聚合根；`SessionBranchId`、`SessionBranchScope`、`RunEventPointer`、`BranchPoint`、`CheckpointId` 是 VO；`RewindResult` 分别携带新 branch、conversation、已恢复 checkpoint 与 residual side effects；`SessionBranchStore`/`FileCheckpointProvider`/`RunEventStore` 是外部状态 port；application `SessionBranchService` 只协调投影与补偿。
- Aggregate Boundary：单个 branch 的创建事实与后续 metadata 是一个 append-only 一致性边界；父 branch 只被引用，不参与 child 创建事务且永不被改写。`RunEventStore` 继续拥有 agent 执行事实，branch 只引用已存在且 scope 匹配的事件坐标；checkpoint 内容由 provider 独立持久化，branch/event 只保存 opaque id。文件恢复是显式补偿，不能和 branch 创建伪装成跨介质事务。
- Invariants：parent event 必须已存在且 sequence 不超过已声明 head；parent point 创建后不可随 run 继续 append 而漂移；fork/rewind 的 session/workspace 必须与 parent 和目标 run 一致，scope 不匹配统一表现为不可用；rewind 必须创建新 branch，旧 branch/run/checkpoint 历史不得删除；同一文件多个 checkpoint 必须按副作用逆序恢复；只有 `FileWrite`/`FileEdit` 成功写入前产生的 checkpoint 可标记为可恢复；read-only 工具不产生副作用事实；Bash/MCP/数据库/远端 API 默认 non-reversible；started-but-unsettled 继续按 #46 报 UNKNOWN，绝不自动重放。
- Variation Point Map：branch 持久化介质收敛到 `SessionBranchStore`；conversation 重建继续复用 `RunEventProjector`；文件捕获/恢复收敛到 `FileCheckpointProvider`；工具副作用分类由 provider-neutral `ToolSafety`/typed `ToolSideEffect` 表达；CLI 仅选择 conversation-only 或 conversation-and-files 并展示 residual，不拥有回滚规则；worktree 策略继续留 coding/platform。
- Refactor Signals：当前 `RunEvent` 只有 invocation started/settled，无法区分只读、checkpointed 与外部副作用，需要增加 typed side-effect fact；`ExecutionContext` 尚未携带 SessionId，文件 checkpoint 无法绑定完整 session/workspace scope；`FileWriteTool`/`FileEditTool` 直接写文件，需要在实际写入前接入 provider，但必须保留先读后写与 workspace boundary；`ChatMemoryStore` 消息快照不能承担 branch 事实存储。
- Review Questions：本阶段只恢复 kernel 自己捕获的 UTF-8 文件内容/存在性与基础 metadata，不承诺目录树、权限 ACL、symlink 或外部进程状态的通用事务恢复。文件补偿中途失败必须返回部分恢复与明确 residual，不能删除已创建 branch 或谎报全成功；多 writer fencing/retention 仍由条件任务 #56 处理。

| 评分维度 | 实施前 | 证据与缺口 |
|---|---:|---|
| 聚合边界是否清晰 | 2/3 | append-only parent/child 边界已确定，但尚无 branch aggregate/store |
| 变化是否被收敛 | 1/3 | conversation、文件补偿和外部副作用仍没有统一 typed 协调合同 |
| 不变量是否可被模型守护 | 1/3 | RunEvent 能守序列/scope，但不能守 branch scope、parent point 或补偿顺序 |
| 行为是否与模型一致 | 1/3 | 当前只能 resume 消息投影，无法表达 fork/rewind 或 residual effect |
| 设计是否支持下一轮需求变化 | 2/3 | append-only run facts 与显式 workspace 已铺路，worktree/外部事务边界也明确 |
| **总分** | **7/15** | Red 先固定五个跨聚合验收场景，再实现最小 branch/checkpoint 合同 |

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

**完成后领域建模复核（2026-07-29）**

- Summary：`SessionBranch` 已成为不可变 parent point/head 的聚合投影，fork/rewind 只创建新 branch journal，旧 run/branch 文件保持字节级不变。`RewindResult` 将 conversation、已恢复/未恢复 checkpoint 与 external residual 分开返回，kernel 不再把消息回退包装成通用副作用回滚。
- Domain Concept Map：`SessionBranch` 是聚合根；`SessionBranchId`、`SessionBranchScope`、`RunEventPointer`、`BranchPoint`、`CheckpointId`/`CheckpointOwner` 是 VO；`BranchOrigin`/`RewindMode` 收敛合法状态；`ToolSideEffect.CheckpointedFile|NonReversible` 是 typed fact；`SessionBranchStore`、`FileCheckpointProvider` 与既有 `RunEventStore` 是独立 port。
- Aggregate Boundary：branch file 只接受一次 `BranchCreated`，以 owner-only JSONL 保存完整不可变 metadata；父 branch 仅按 `(branchId, runId, sequence)` 引用，不参与 child 写入。run facts、branch journal 与 checkpoint snapshot 是三个介质边界；application 先保存新 branch，再执行显式文件补偿，失败不会回删审计事实或伪装成跨介质原子事务。
- Invariants：目标 run event 必须存在且 session/workspace 与 branch 完全匹配；目标 sequence 不能越过 parent head；错 scope 与 unknown branch 统一 unavailable；rewind 只新增 branch，不改 parent；多个 checkpoint 按 side-effect 逆序恢复；conversation-only 把 checkpoint 放进 `unrestoredCheckpoints`；恢复失败继续返回 branch、unrestored 与 residual；read-only 工具无 side-effect event，默认 mutating/Bash/MCP 是 non-reversible；FileWrite/Edit 只有拿到写前 checkpoint 才产生 checkpointed fact。
- Variation Point Map：branch 介质在 `SessionBranchStore`，文件内容/存在性/mtime 补偿在 `FileCheckpointProvider`，tool effect 分类在 provider-neutral `ToolSafety.reversibility`，事实持久化在 `ToolSideEffectObserved`，conversation 继续复用 `RunEventProjector`。CLI 组合根只注入本地 checkpoint provider；具体 fork/rewind 命令展示留 #55，worktree 继续属于 coding/platform。
- Refactor Signals：`ExecutionContext` 现显式携带 SessionId，checkpoint owner 不再由全局推断；`TruncatingTool`/`GovernedTool` 透传 delegate safety，避免装饰器把 checkpointed mutation 降级；checkpoint/branch 目录分别尝试 `0700`、文件 `0600`，opaque ID 以安全编码派生文件名。完整 checkpoint payload 为本机 L0 恢复所需，和 suspension 一样依赖 owner-only 保护而非审计脱敏。
- Remaining Limitations：当前补偿只覆盖 kernel 自己捕获的普通文件内容、存在性与 mtime；不覆盖目录树、ACL、symlink、Bash/MCP/数据库/远端 API、后台进程或 worktree merge。branch 创建/选择尚无 CLI 命令，按任务边界留 #55；多 writer fencing、索引和 retention 仍只在 #56 的真实规模条件触发。

| 评分维度 | 完成后 | 证据 |
|---|---:|---|
| 聚合边界是否清晰 | 3/3 | branch、run facts 与 checkpoint 三个介质/一致性边界显式分离 |
| 变化是否被收敛 | 3/3 | journal、projection、checkpoint、effect classification 与 host wiring 各守单一变化轴 |
| 不变量是否可被模型守护 | 3/3 | typed scope/pointer/origin/mode、immutable create、逆序补偿与 residual 结果 |
| 行为是否与模型一致 | 3/3 | rewind 创建 child、旧历史字节不变，文件恢复与外部残余分别报告 |
| 设计是否支持下一轮需求变化 | 3/3 | port 可替换介质，typed event 可供 CLI/Web 使用，同时拒绝通用事务/worktree 下沉 |
| **总分** | **15/15** | 五个 Red 场景及逆序、部分失败、codec、owner/path、安全分类与 CLI wiring 均有测试 |

**blockedBy**：#43、#46；若需后台进程状态，还 blockedBy #50。

---

### #53 [Kernel-TDD, P3] provider-neutral `ModelPolicy` / `RetryPolicy`

**状态**：进行中（Green，2026-07-29）。

**实施前领域建模审计**

- commands / facts：一次逻辑 assistant turn 发出 `request model`，每个物理调用形成 `model attempt started → usage observed → completed/failed`；只有完整 `AiMessage` 被接受前的可重试失败才能产生下一 attempt。`AssistantTurnReceived`、tool started/settled 和 side-effect fact 一旦形成，均不属于 model retry 的重放单元。
- ubiquitous language：`ModelTier` 是角色请求的 provider-neutral 能力档位；`ModelIdentity` 是实际 provider/model；`ModelPolicy` 描述 primary/fallback route；`RetryPolicy` 根据 typed `ProviderFailureKind`、attempt 序号和 provider retry-after 形成有限退避；`ModelUsage` 是实际模型维度的 usage/audit 投影。authentication/config/schema/context-overflow 不是 transient retry 的同义词。
- 聚合/一致性边界：单次 LLM attempt 在拿到完整 assistant turn 前原子结束；一个逻辑 turn 可包含多个 attempt，但只接受一个 assistant message。conversation/tool batch 是更外层边界，model policy 无权删除消息、回退 tool result 或重新 dispatch 已 settled invocation。
- 核心不变量：retry/fallback attempt 显式消耗 `maxLlmCalls`、token usage 和同一 run deadline；backoff 不能越过 deadline，cancel 仍向当前 call 传播；context overflow 继续只走 `ContextPolicy` 的单次 compact/retry；fallback 默认关闭；未知、认证、配置和 schema 失败默认不重试；实际尝试过的 provider/model 即使报告零 token 也进入审计投影。
- 变化点：tier→client/model 收敛到 `LlmClientSelector`；失败分类收敛到 provider adapter 的 domain exception 映射；次数/退避/jitter/retry-after 收敛到 `RetryPolicy`；等待时间收敛到可替换 sleeper/clock；executor 只编排 policy decision，不出现 Anthropic/OpenAI 分支。
- 反模式警戒：不在 executor 按异常文本猜 provider；不在 executor 外重跑整个 run；不把 LLM attempt 冒充 assistant turn；不让 fallback 静默丢失实际 model identity；不让通用 retry 抢走 context overflow 的专用恢复语义。
- 实施前评分 **8/15**：聚合边界 2、变化收敛 2、不变量守护 1、行为一致 2、下一轮演进 1。已有 `LlmCall`、deadline、共享 budget、`ContextPolicy` 和 `LlmClientSelector` 是可复用承重点；缺口是 typed failure、attempt budget、route/retry decision、可测试退避与 model usage projection。

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
