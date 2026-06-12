# 诊断 Agent 通用能力设计方案

> 面向 `claude-code-langchain4j` 从“进程内诊断引擎”演进为“可靠线上问题诊断 Agent”的能力设计。
>
> @author zhourui(V33215020)
> @since 2026-06-11
>
> 修订 2026-06-11：补状态快照跨轮重建（6.8）、主 Agent 预算（7.4）、计划约束强制点（7.5）、tool-use 结构化输出（13.2）、层归属下沉（EvidenceLedger / 报告校验入 domain）、need_info 与 state 事件（9）、失败重试（11.5）、离线评测集（15.4）；Task 诊断化降为 P2。

---

## 1. 背景

当前项目已经完成诊断引擎化的底座能力：

- `DiagnoseEngine` 作为 agent-web 进程内调用门面。
- `AgentExecutor` 支持多轮 LLM + tool-use 循环。
- `ClaudeStreamJsonListener` 输出 agent-web 可消费的 stream-json。
- `ReadOnlyPermissionPolicy` 提供只读硬约束。
- 已有 ES、MySQL、Redis、HTTP GET、Dubbo 等只读工具雏形。
- 已有结果截断、上下文压缩、停止运行、usage 透出、`SubAgentTool`。

这些能力足够支撑“简单诊断问答”和“单步工具查询”，但还不足以支撑稳定的线上问题诊断。真实诊断需要持续维护问题定义、诊断计划、假设、证据、任务状态和结论质量，不能只依赖模型在自由文本中自行组织。

本文设计下一阶段需要补齐的通用能力：`Plan`、`Task`、`Evidence Ledger`、诊断提示词、工具治理和结构化结论。

---

## 2. 目标与边界

### 2.1 目标

1. 引入诊断计划，使 Agent 在查工具前明确问题边界、假设和检查步骤。
2. 引入证据账本，使每个结论都能追溯到工具结果或用户输入。
3. 引入诊断 Task，使复杂问题可以拆成多个受控子任务并汇总。
4. 保持只读诊断，不引入修复写操作。
5. 保持 agent-web 作为会话和 UI 的 single source of truth。
6. 保持当前 DDD 四层结构，不引入 Spring、Guice、Lombok。
7. 让 Skill 承载诊断知识和流程，Java Tool 承载生产级可执行能力。

### 2.2 非目标

- 不复刻 Claude Code 的文件修改、代码生成、提交等开发工作流。
- 不把 Bash 作为默认诊断能力开放。
- 不在本项目内实现 Web、SSE、鉴权、会话落库。
- 不把所有业务诊断流程硬编码进 Java。
- 不以 Skill 脚本替代核心生产工具治理。

---

## 3. 现状评估

### 3.1 已具备能力

| 能力 | 当前状态 | 说明 |
|---|---|---|
| LLM 主循环 | 已有 | `AgentExecutor` 支持 tool-use 循环 |
| 进程内门面 | 已有 | `DiagnoseEngine` / `DefaultDiagnoseEngine` |
| stream-json | 已有 | agent-web 可复用 Claude 消费路径 |
| 只读权限 | 已有 | `ReadOnlyPermissionPolicy` |
| 只读工具 | 部分已有 | ES / MySQL / Redis / HTTP / Dubbo |
| 子 Agent | 已有雏形 | `SubAgentTool` 名称为 `Task` |
| 截断与压缩 | 已有雏形 | `TruncatingTool` / `ContextCompactionService` |
| usage 透出 | 已有 | `LangChain4jLlmClient` 已透出 Anthropic `cache_read_input_tokens`，非 Anthropic usage 保持 0 不估算 |

### 3.2 关键缺口

| 缺口 | 影响 |
|---|---|
| 缺少诊断 Plan 一等对象 | 模型容易无计划查工具，结论不可控 |
| 缺少 Evidence Ledger | 结论无法强制绑定证据 |
| 结构化状态无法跨轮存活 | 引擎无状态、每轮从 history 重建，Plan / Hypothesis / Evidence 若只存在于自由文本，第二轮全部归零 |
| 主 Agent 无预算上限 | 诊断跑飞只能人工 stop，成本与延迟不可控 |
| Task 缺少诊断语义 | 子 Agent 只是自由文本任务，缺少假设、预算、输出约束 |
| 诊断系统提示词未形成闭环 | 只读、证据优先、输出格式依赖模型临场发挥 |
| 工具装配不完整 | 工具类存在，但生产注册、配置、脱敏、审计未闭环 |
| Skill 与 Tool 边界未固化 | 知识流程和执行能力容易混在一起，治理困难 |
| 安全治理不足 | LLM 请求响应日志、敏感字段、工具 allowlist 仍需明确 |
| 验收链路不足 | 缺少 agent-web native 端到端验证 |

---

## 4. 总体设计

诊断 Agent 分为四层能力：

```text
User / agent-web
  |
  v
DiagnoseEngine
  |
  v
DiagnosisOrchestrator
  |-- DiagnosisPlanner       通过 kernel StructuredOutputTool 生成/更新诊断计划
  |-- DiagnosisCase          聚合根：状态机 + EvidenceLedger
  |-- PlanGuardPolicy        工具调用的计划约束校验
  |-- AgentBudgetGuard       kernel 预算检查与强制收敛
  |-- DiagnosisTaskRunner    执行受控子任务
  |-- DiagnosisReporter      生成结构化结论
  |
  v
AgentExecutor
  |
  v
ToolRegistry
  |-- LogQueryTool
  |-- EsReadTool
  |-- MysqlReadTool
  |-- RedisReadTool
  |-- HttpGetTool
  |-- DubboInvokeTool
```

`AgentExecutor` 继续作为 kernel 通用 LLM + tool-use 循环，不承载诊断业务规则。诊断语义收在 `cclc-agent-diagnosis`，以 `interfaces/engine` 门面调用方式包住 kernel 执行器；CLI 只在 `cclc-cli` 作为调试入口存在，不进入宿主 classpath。

---

## 5. Skill 与 Tool 的职责边界

### 5.1 Skill

Skill 用于承载诊断知识、业务背景和 SOP。

适合内容：

- Qpon 订单失败排查流程。
- 优惠券核销失败排查流程。
- Dubbo 超时排查经验。
- Redis 缓存不一致排查步骤。
- 某服务常见错误码解释。
- 需要调用哪些工具、如何选择时间窗、如何缩小范围。

Skill 不应该直接承担核心生产能力的治理职责。

落地决策（2026-06-11，已被 DESIGN.md §16.4 于 2026-06-13 推翻）：DESIGN.md 曾把 Skill 子系统列为 out of scope，本项目 MVP 不引入可执行 Skill 机制。该阶段诊断知识以 PromptPack 形式落地：

- 每个业务场景一份 markdown SOP，存放于 `prompts/diagnosis/`。
- `SystemPromptComposer` 按场景把 SOP 拼接在 system prompt 稳定前缀之后，不破坏 prompt cache。
- PromptPack 只含知识与流程，不含可执行脚本。
- 2026-06-13 起引入知识型 Skill 子系统：PromptPack 保留为常驻必读知识，场景 SOP 和附属资料迁移到 Skill，由模型按 description 选择并通过 `Skill` 工具展开。

### 5.2 Java Tool

Java Tool 用于承载生产级、可审计、可限流、可脱敏的执行能力。

适合内容：

- `LogQueryTool`
- `MysqlReadTool`
- `RedisReadTool`
- `EsReadTool`
- `HttpGetTool`
- `DubboInvokeTool`

Tool 必须具备：

- 明确 input schema。
- 只读能力声明。
- 超时控制。
- 返回结果截断。
- 敏感字段脱敏。
- 调用审计。
- 错误码和错误信息规范。
- 可单元测试的后端 client seam。

### 5.3 组合方式

Skill（MVP 以 PromptPack 形态落地）是经验层，Tool 是执行层。推荐模式：

```text
Skill 指导 Agent 如何拆解问题
  -> Plan 形成检查步骤
  -> Agent 通过 Tool 查询证据
  -> Evidence Ledger 记录证据
  -> Reporter 输出结论
```

---

## 6. 核心领域模型

### 6.1 DiagnosisCase

一次诊断过程的内存态聚合，由 agent-web 持久化会话，本引擎每次从 history 重建。

字段建议：

| 字段 | 说明 |
|---|---|
| `caseId` | 对应 agent-web sessionId |
| `question` | 当前用户问题 |
| `scope` | 环境、服务、时间窗、用户、订单、traceId |
| `plan` | 当前诊断计划 |
| `ledger` | 证据账本 |
| `tasks` | 子任务列表 |
| `status` | `PLANNING` / `RUNNING` / `NEED_INFO` / `DONE` / `FAILED` |

DiagnosisCase 是聚合根，不是字段袋。状态迁移、证据准入、假设判定收在聚合方法内，application 层禁止用 getter 重组这些规则：

```java
public final class DiagnosisCase {

    public void adoptPlan(DiagnosisPlan plan);          // PLANNING -> RUNNING，校验 plan 不变量

    public Evidence recordToolEvidence(ToolUseRequest request, ToolResult result);

    public void markStep(String stepId, StepStatus status, String resultSummary);

    public void judgeHypothesis(String hypothesisId, HypothesisStatus status, List<String> evidenceIds);

    public boolean canConfirmRootCause(String hypothesisId);

    public void requireInputs(List<String> missingInputs); // RUNNING -> NEED_INFO
}
```

聚合不变量（直接对应单测）：

- 状态机仅允许 `PLANNING -> RUNNING -> (NEED_INFO | DONE | FAILED)`，`NEED_INFO` 可回到 `RUNNING`。
- 假设置为 `CONFIRMED` 前，`canConfirmRootCause` 必须为真（至少一个非 `MODEL_INFERENCE` 证据）。
- `recordToolEvidence` 必须能关联到一个非 `PENDING` 的 step，关联不上视为 off-plan 调用（见 7.5）。

### 6.2 DiagnosisPlan

诊断计划是一等对象，而不是普通文本。

```java
public record DiagnosisPlan(
        String problemStatement,
        DiagnosisScope scope,
        List<Hypothesis> hypotheses,
        List<DiagnosisStep> steps,
        List<String> missingInputs
) {
}
```

设计约束：

- 每个 plan 必须有问题定义。
- 每个 step 必须说明要验证哪个 hypothesis。
- 每个 step 必须绑定允许使用的工具类型。
- 每次工具执行后允许更新 plan，但必须保留历史版本。历史版本随状态快照往返（见 6.8），只存 problemStatement 与 step 状态的 diff 摘要，不存全量。

### 6.3 DiagnosisScope

```java
public record DiagnosisScope(
        String environment,
        String serviceName,
        TimeWindow timeWindow,
        String traceId,
        String userId,
        String orderId,
        Map<String, String> tags
) {
}

public record TimeWindow(Instant start, Instant end) {
    // 构造校验：start 必须早于 end；跨度上限由策略配置（如 prod 默认不超过 24 小时）
}
```

用途：

- 避免工具查询无限扩大范围。
- 为日志、DB、ES、Redis 查询提供默认参数。
- 作为安全策略输入，例如 prod 环境限制更严格。

时间窗不用自由文本字符串：11.4 的“prod 查询必须带时间窗”要做成可校验规则，前提是时间窗是类型化的值对象。

### 6.4 Hypothesis

```java
public record Hypothesis(
        String id,
        String statement,
        double confidence,
        HypothesisStatus status,
        List<String> supportingEvidenceIds,
        List<String> contradictingEvidenceIds
) {
}
```

状态：

- `OPEN`
- `SUPPORTED`
- `CONTRADICTED`
- `INSUFFICIENT_EVIDENCE`
- `CONFIRMED`

### 6.5 DiagnosisStep

```java
public record DiagnosisStep(
        String id,
        String goal,
        String hypothesisId,
        List<String> allowedTools,
        StepStatus status,
        String resultSummary
) {
}
```

状态：

- `PENDING`
- `RUNNING`
- `DONE`
- `SKIPPED`
- `FAILED`

### 6.6 Evidence

```java
public record Evidence(
        String id,
        EvidenceSource source,
        String summary,
        String rawExcerpt,
        String toolName,
        String toolUseId,
        Map<String, Object> metadata,
        Instant observedAt
) {
}
```

证据来源：

- `USER_INPUT`
- `TOOL_RESULT`
- `SYSTEM_CONTEXT`
- `TASK_REPORT`
- `MODEL_INFERENCE`

约束：

- `MODEL_INFERENCE` 不能单独支撑根因结论。
- 最终根因必须引用至少一个非 `MODEL_INFERENCE` 证据。
- `rawExcerpt` 必须经过截断和脱敏。

以上是 domain 不变量，由 `DiagnosisCase` 聚合与 `DiagnosisReportValidator`（领域服务）强制；Reporter 只做编排，不自带校验逻辑。

### 6.7 DiagnosisReport

最终输出结构：

```java
public record DiagnosisReport(
        String summary,
        List<RootCauseCandidate> rootCauseCandidates,
        List<EvidenceRef> keyEvidence,
        List<String> recommendedActions,
        List<String> missingInformation,
        double confidence,
        boolean needHumanCheck
) {
}
```

### 6.8 状态快照与跨轮重建

引擎无状态、agent-web 持久化会话，意味着 DiagnosisCase 必须能跨轮存活。若 Plan、假设状态、证据索引只存在于内存和自由文本输出里，第二轮重建时全部丢失，6.4 的假设状态机名存实亡。这是 Evidence Ledger 落地的前置决策。

方案：状态快照随 stream-json 往返。

1. 每轮 `result` 事件之前，引擎输出 `diagnosis_state` 扩展事件，内容为 DiagnosisCase 快照：plan 当前版本、hypotheses、step 状态、evidence 索引。
2. agent-web NATIVE 路径按 session 持久化最新快照，并在下一轮 `RunRequest` 以独立字段 `stateSnapshot` 回传，不混入 message history，不进入 LLM 上下文。
3. 引擎重建顺序：有快照则反序列化恢复；快照缺失或 `schemaVersion` 不兼容则降级——从 history 重新生成 plan，假设状态归零，报告标注 `degraded: true`。
4. 快照只存摘要与引用：evidence 存 id、summary、toolUseId，原文片段仍从 history 的 `tool_result` 按 toolUseId 取，避免快照膨胀。
5. 序列化由 `DiagnosisStateCodec`（infrastructure）承担并管理 `schemaVersion`，domain 不感知 JSON。

旧前端兼容：忽略 `diagnosis_state` 事件不报错，后果是每轮降级重建——功能可用、质量下降。

---

## 7. 诊断主流程

### 7.1 单轮流程

```text
1. agent-web 调用 DiagnoseEngine.runStream
2. 引擎按 history 重建 Conversation 和 DiagnosisCase
3. DiagnosisPlanner 生成或更新 DiagnosisPlan
4. 将计划以 stream-json 增量输出给 agent-web
5. AgentExecutor 按计划执行工具
6. ToolResult 进入 EvidenceLedger
7. Planner 根据证据更新假设和步骤状态
8. 若信息不足，输出 need_info
9. 若证据足够，DiagnosisReporter 输出结构化诊断报告
10. result 事件结束本轮
```

### 7.2 计划生成规则

Plan 生成时必须遵守：

- 先识别问题类型和影响范围。
- 如果缺少时间窗、环境、服务、traceId 等关键字段，优先追问。
- 如果用户提供的信息足够，先查最小范围证据。
- 每个工具调用必须对应一个 step。
- 不允许为了“多查一点”扩大到无边界查询。

### 7.3 证据优先规则

Reporter 输出根因时必须遵守：

- 没有证据时只能输出假设，不能输出确认根因。
- 结论必须标注置信度。
- 建议必须区分“可立即执行”和“需要人工确认”。
- 工具失败不能被忽略，必须进入 missingInformation 或 risk。

### 7.4 预算与终止

主 Agent 自身必须有预算，不能只依赖人工 stop。预算机制属于 kernel 通用能力，诊断层只配置数值：

```java
public record AgentBudget(
        int maxTurns,
        int maxToolCalls,
        long maxInputTokens
) {
}
```

- `AgentExecutor` / `DiagnosisOrchestrator` 在每轮开始和每次工具调用前检查预算。
- 超预算不静默截断：强制进入 Reporter，基于已有证据出报告，`needHumanCheck = true`，置信度按证据完整度折减。
- Task 预算（8.2）从主预算中扣减，防止子任务绕过总额。

### 7.5 计划约束的强制点

“每个工具调用必须对应一个 step”只写进 prompt 就只是建议，必须有代码强制点：

- domain 提供 `DiagnosisPlan.isToolAllowed(String toolName)`：当前存在 `RUNNING` 或 `PENDING` step 且其 `allowedTools` 包含该工具才放行。
- application 提供 `PlanGuardPolicy`，与 `ReadOnlyPermissionPolicy` 组成校验链：先只读校验，再计划校验；规则本体在 domain，policy 只做适配。
- 违规处理分两档：MVP 为 observe 模式——放行但记入审计，Evidence metadata 标注 `offPlan: true`；硬化阶段切 deny 模式——拒绝执行，把结构化错误回传模型，提示其先更新计划。
- 模式开关进配置，默认 observe，避免计划质量不稳定时把 Agent 卡死。

---

## 8. Task / SubAgent 设计

### 8.1 为什么需要 Task

复杂问题通常包含多个并行假设：

- 入口服务是否报错。
- 下游 Dubbo 是否超时。
- DB 状态是否异常。
- Redis 缓存是否与 DB 不一致。
- 配置或活动规则是否命中异常。

如果全部由主 Agent 串行自由探索，容易出现上下文膨胀、路径漂移和结论混乱。Task 用于把一个假设的验证过程隔离到独立上下文。

### 8.2 Task 输入

现有 `SubAgentTool` 的输入只有 `prompt`，需要诊断化：

```json
{
  "taskType": "LOG_TRACE",
  "hypothesisId": "H1",
  "goal": "验证入口服务在用户下单时是否抛出库存异常",
  "scope": {
    "environment": "prod",
    "serviceName": "qpon-order",
    "timeRange": "2026-06-11 10:00:00~10:15:00",
    "traceId": "..."
  },
  "allowedTools": ["LogQuery", "EsRead"],
  "budget": {
    "maxToolCalls": 5,
    "timeoutSeconds": 60
  }
}
```

### 8.3 Task 输出

```json
{
  "taskId": "T1",
  "hypothesisId": "H1",
  "status": "DONE",
  "summary": "入口服务在该 trace 下出现库存校验失败",
  "evidence": [
    {
      "source": "LogQuery",
      "summary": "qpon-order ERROR inventory check failed",
      "rawExcerpt": "..."
    }
  ],
  "confidence": 0.78,
  "missingInformation": []
}
```

### 8.4 Task 调度策略

MVP 阶段：

- 主 Agent 串行调用 `Task`。
- 每个 Task 独立 `Conversation`。
- 每个 Task 使用 narrowed `ToolRegistry`。
- Task 结果作为 `TASK_REPORT` 写入 Evidence Ledger。

增强阶段：

- 对互不依赖的 Task 并行调度。
- 限制全局并发数。
- 支持任务超时和取消传播。
- 支持 Task 级别 usage 统计。

---

## 9. Plan 与 stream-json 事件

agent-web 当前主要消费 Claude stream-json。为兼容现有前端，MVP 可以先把 Plan 和 Report 作为普通 assistant 文本输出，同时在 result 中包含最终文本。

诊断扩展事件经 kernel 的 `ExtensionEventEmitter` hook 输出。增强阶段建议新增可忽略的扩展事件：

```json
{
  "type": "diagnosis_plan",
  "session_id": "...",
  "plan": {
    "problemStatement": "...",
    "hypotheses": [],
    "steps": []
  }
}
```

```json
{
  "type": "diagnosis_evidence",
  "session_id": "...",
  "evidence": {
    "id": "E1",
    "summary": "...",
    "toolName": "LogQuery"
  }
}
```

```json
{
  "type": "diagnosis_need_info",
  "session_id": "...",
  "missing": ["timeWindow", "environment"],
  "question": "请提供问题发生的时间窗和环境"
}
```

```json
{
  "type": "diagnosis_state",
  "session_id": "...",
  "schemaVersion": 1,
  "snapshot": { "plan": {}, "hypotheses": [], "evidenceIndex": [] }
}
```

MVP 阶段 need_info 仍以普通文本追问表达，`diagnosis_need_info` 供前端结构化展示；`diagnosis_state` 不是展示事件，是状态往返通道（见 6.8），NATIVE 路径必须持久化并回传。

兼容原则：

- 新事件必须可被老前端安全忽略。
- 扩展事件由诊断层定义，kernel 只保证事件透传和顺序，不感知识别诊断语义。
- 现有 `stream_event`、`user`、`result` 不破坏。
- `result` 仍作为 turn end 的唯一判断。
- 忽略 `diagnosis_state` 只导致降级重建，不导致错误。

---

## 10. 诊断提示词设计

### 10.1 System Prompt 目标

诊断 Agent 的系统提示词必须明确：

- 只做诊断，不执行修复。
- 不臆断根因，结论必须引用证据。
- 查询前先界定范围。
- 优先使用最小必要工具调用。
- 对敏感信息做摘要，不重复输出完整敏感数据。
- 最终输出结构稳定。

### 10.2 Prompt 骨架

```text
你是线上问题诊断 Agent，负责基于用户描述和只读工具定位问题。

工作原则：
1. 先明确问题、环境、时间窗、关键实体。
2. 若关键信息不足，先追问，不要盲目查询。
3. 每个工具调用必须服务于一个明确假设。
4. 只允许只读诊断，不允许修改系统状态。
5. 最终结论必须引用证据；无证据只能表达为假设。

输出格式：
- 问题理解
- 诊断计划
- 已验证证据
- 根因候选
- 建议动作
- 置信度
- 待确认信息
```

### 10.3 接入要求

当前 `ChatRequest` 已支持 `systemPrompt`，但执行器构造请求时没有注入。需要在实现阶段补齐：

- `AgentExecutor` 接收 `SystemPromptComposer` 或静态 system prompt。
- `DefaultDiagnoseEngine` 使用诊断专用 prompt。
- CLI 调试入口继续可用，但不作为诊断主入口。

---

## 11. 工具治理设计

### 11.1 工具清单

| 工具 | 用途 | 优先级 |
|---|---|---|
| `LogQueryTool` | 按 traceId、关键词、服务、时间窗查日志 | P0 |
| `MysqlReadTool` | 查询业务状态、配置、关联数据 | P0 |
| `RedisReadTool` | 查询缓存、TTL、类型、一致性 | P0 |
| `EsReadTool` | 查询索引、聚合、映射 | P1 |
| `DubboInvokeTool` | 只读接口验证 | P1 |
| `HttpGetTool` | 只读 HTTP 验证 | P1 |
| `Task` | 子任务诊断 | P1 |

### 11.2 统一包装

所有诊断工具注册前统一包装：

```text
Raw Tool
  -> ReadOnlyPermissionPolicy
  -> GovernedTool(kernel: Timeout / Audit / Redaction)
  -> TruncatingTool
  -> ToolRegistry
```

治理包装机制在 kernel，诊断层负责提供具体规则、audit sink、后端 client seam 与工具注册。

### 11.3 脱敏规则

默认脱敏：

- 手机号
- 身份证号
- token
- cookie
- password
- secret
- access key
- 用户地址
- 支付流水敏感字段

脱敏发生点：

- ToolResult 进入 Conversation 前。
- ToolResult 写入 Evidence 前。
- LLM request/response 日志前。

### 11.4 生产默认配置

- 默认关闭 LLM request/response 明文日志。
- prod 环境工具查询必须有时间窗。
- MySQL 默认 `maxRows <= 100`。
- Redis 禁止 `KEYS *` 或对大 keyspace 的无界扫描。
- Dubbo 仅允许显式 allowlist 方法。
- HTTP GET 仅允许 allowlist host。

### 11.5 失败与重试

- 诊断工具全部只读幂等，瞬时失败（超时、连接拒绝）允许原参数重试 1 次，固定短退避。
- 重试仍失败：生成失败 Evidence（metadata 记录错误码），进入报告 `missingInformation`，禁止静默吞掉。
- 参数类错误不重试，把结构化错误作为 tool_result 回传，由模型修正参数。

---

## 12. 落地模块设计

### 12.1 新增包建议

```text
cclc-kernel/
  application/
    AgentExecutor
    AgentBudgetGuard
  domain/agent/
    AgentBudget
  infrastructure/streamjson/
    ClaudeStreamJsonListener
    ExtensionEventEmitter
  infrastructure/tools/
    StructuredOutputTool
    TruncatingTool
    governance/
      GovernedTool
      ToolGovernance

cclc-agent-diagnosis/
  interfaces/engine/
    DiagnoseEngine
    DefaultDiagnoseEngine
    DiagnosisOrchestrator
    DiagnoseEngineBuilder

  application/diagnosis/
    PlanGuardPolicy           // 适配 PermissionPolicy，规则本体在 domain

  domain/diagnosis/
    DiagnosisCase             // 聚合根：状态机、证据准入、假设判定
    DiagnosisPlan
    DiagnosisScope
    TimeWindow
    DiagnosisStep
    Hypothesis
    Evidence
    EvidenceLedger            // 聚合内部实体，证据约束在此强制
    DiagnosisReport
    DiagnosisReportValidator  // 领域服务：根因-证据绑定校验

  infrastructure/tools/
    LogQueryTool
    EsReadTool / MysqlReadTool / RedisReadTool / HttpGetTool / DubboInvokeTool

  infrastructure/diagnosis/
    DiagnoseToolFactory
    DiagnosisToolBackends
    StructuredDiagnosisPlanner
    StructuredDiagnosisReporter
    DiagnosisStateCodec       // 快照序列化，schemaVersion 管理

cclc-cli/
  interfaces/cli/
    CclcApplication
    JLine REPL
```

分层约束：

- `domain/diagnosis` 不依赖 application、interfaces、infrastructure。
- `application/diagnosis` 只依赖 domain 和 port。
- `interfaces/engine` 编排 application。
- `infrastructure` 实现工具、脱敏、审计输出。
- `cclc-agent-diagnosis` 只依赖 `cclc-kernel`，不得依赖 `cclc-cli` / JLine。

`EvidenceLedger` 和报告校验放 domain 而不是 application：“`MODEL_INFERENCE` 不能单独支撑根因”是不变量，不是编排，留在 application 就是 App 层业务逻辑泄漏。

### 12.2 与现有类关系

| 现有类 | 后续关系 |
|---|---|
| `AgentExecutor` | 保持通用执行器，补 system prompt 注入 |
| `DefaultDiagnoseEngine` | 升级为调用 `DiagnosisOrchestrator` |
| `SubAgentTool` | 升级输入输出 schema，成为诊断 Task |
| `ClaudeStreamJsonListener` | 归属 kernel，保持兼容，通过 `ExtensionEventEmitter` 透传诊断扩展事件 |
| `ReadOnlyPermissionPolicy` | 保持硬约束 |
| `TruncatingTool` | 作为所有大结果工具的默认包装 |

---

## 13. 关键接口草案

### 13.1 DiagnosisOrchestrator

```java
public final class DiagnosisOrchestrator {

    public DiagnosisResult run(DiagnosisRequest request,
                               AgentEventListener listener,
                               CancellationToken cancel) {
        // 1. 重建 case
        // 2. 生成 plan
        // 3. 执行 plan
        // 4. 记录 evidence
        // 5. 输出 report
    }
}
```

### 13.2 DiagnosisPlanner

```java
public interface DiagnosisPlanner {

    DiagnosisPlan createPlan(DiagnosisCase diagnosisCase);

    DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence);
}
```

计划由 LLM 生成，但不解析自由文本，结构化输出走 kernel `StructuredOutputTool` 的 tool-use 强制 schema：

- 诊断层注册计划输出 schema，input schema 即 `DiagnosisPlan` 的 JSON Schema；planner 调用 LLM 时以 `tool_choice` 强制命中结构化输出工具。
- 报告输出同理，承接 `DiagnosisReport`。
- 结构化输出工具不执行任何外部操作，只做 schema 校验加反序列化，不进入诊断工具的审计与脱敏链。
- 解析失败：把校验错误作为 tool_result 回传重试一次；再失败降级为 NEED_INFO，原始文本作为草稿输出，不让流程崩溃。

后续可加入规则模板，例如按业务场景选择预设计划。

### 13.3 EvidenceLedger（domain，聚合内部）

application 不直接持有 ledger，证据一律经 `DiagnosisCase.recordToolEvidence` 等聚合方法写入：

```java
public final class EvidenceLedger {

    public Evidence addUserInput(String text);

    public Evidence addToolResult(ToolUseRequest request, ToolResult result);

    public Evidence addTaskReport(TaskReport report);

    public List<Evidence> all();
}
```

### 13.4 DiagnosisReporter

```java
public interface DiagnosisReporter {

    DiagnosisReport report(DiagnosisCase diagnosisCase);
}
```

报告输出前必须通过校验（校验逻辑在 domain 的 `DiagnosisReportValidator`，Reporter 只编排调用）：

- 根因候选引用的证据是否存在。
- 是否存在未完成关键 step。
- 是否存在工具失败。
- 是否需要人工确认。

---

## 14. 实施阶段

### Phase 1: Prompt、Plan 与预算 MVP

目标：

- 补齐诊断 system prompt 注入。
- 引入 `DiagnosisCase`、`DiagnosisPlan`、`DiagnosisStep`、`Hypothesis`。
- 基于 kernel `StructuredOutputTool` 实现 tool-use 结构化计划输出。
- 引用 kernel `AgentBudget`，超预算强制收敛出报告。
- 首轮输出诊断计划，每次工具调用前后更新 step 状态。

验收：

- 用户给出 traceId 和时间窗时，Agent 能先输出计划再查日志。
- 用户缺少关键字段时，Agent 优先追问。
- 计划解析失败有重试和降级，不崩流程。
- 超预算时输出带 `needHumanCheck` 的报告而不是继续查。
- `mvn test` 下聚合不变量与 planner 单测通过。

### Phase 2: Evidence Ledger 与状态快照

目标：

- 引入 `Evidence` 和 `EvidenceLedger`（domain）。
- 工具结果自动转证据，最终报告必须引用证据。
- `diagnosis_state` 快照事件、`stateSnapshot` 回传、`DiagnosisStateCodec`。
- 快照缺失时的降级重建路径。

验收：

- 无工具证据时不能输出“确认根因”。
- 工具失败会进入报告的待确认项。
- 截断和脱敏后的证据进入模型上下文。
- 第二轮对话能从快照恢复假设状态；无快照时降级重建且报告标注 degraded。

### Phase 3: Tool 生产治理

目标：

- 实现 `LogQueryTool`。
- 引入 `DiagnoseToolFactory`。
- 增加脱敏、审计、超时、allowlist。
- 默认关闭 LLM request/response 明文日志。

验收：

- prod 查询必须带时间窗。
- MySQL / Redis / Dubbo / HTTP 均有安全测试。
- 工具调用日志不包含敏感原文。

### Phase 4: agent-web 端到端

目标：

- agent-web 增加 `AgentType.NATIVE`。
- 接入 native `DiagnoseEngine`。
- 前端展示计划、工具、证据、报告。

验收：

- 纯文本诊断通过。
- 工具诊断通过。
- stop 通过。
- 历史回放通过。
- 长日志截断和长会话压缩通过。
- 快照往返通过：跨轮恢复假设状态，无快照降级不报错。

### Phase 5: Task 诊断化

目标：

- 升级 `SubAgentTool` schema。
- Task 绑定 hypothesis、scope、allowedTools、budget。
- Task 输出结构化 `TaskReport`，预算从主预算扣减。

验收：

- 主 Agent 能把日志链路和缓存一致性拆为两个 Task。
- 子任务不能调用未授权工具。
- stop 能取消子任务。

排序说明：Task 诊断化后移——`SubAgentTool` 经 narrowed `ToolRegistry` 已有隐式工具隔离，schema 升级的收益低于状态快照与工具治理。

---

## 15. 测试策略

### 15.1 单元测试

| 测试类 | 覆盖 |
|---|---|
| `DiagnosisCaseTest` | 聚合状态机、证据准入、假设判定不变量 |
| `DiagnosisPlanTest` | plan 状态流转、`isToolAllowed` |
| `EvidenceLedgerTest` | 证据写入、脱敏、引用 |
| `DiagnosisReporterTest` | 无证据不确认根因 |
| `PlanGuardPolicyTest` | observe / deny 两种模式、off-plan 标注 |
| `AgentExecutorBudgetTest` / `DiagnosisOrchestratorTest` | 超预算强制收敛 |
| `DiagnosisStateCodecTest` | 快照序列化、schemaVersion 兼容、损坏快照降级 |
| `DiagnosisTaskRunnerTest` | allowedTools、timeout、cancel |
| `DiagnoseToolFactoryTest` | 工具注册和包装顺序 |
| `RedactionServiceTest` | 敏感字段脱敏 |

### 15.2 集成测试

- `DefaultDiagnoseEngineTest` 增加 plan/evidence/report 流程。
- `ClaudeStreamJsonListenerTest` 增加诊断扩展事件兼容性。
- `AgentExecutorTest` 增加 system prompt 注入验证。
- 每个 Tool 使用 stub client，不连真实外部系统。

### 15.3 端到端测试

在 agent-web 侧验证：

- `NATIVE` agent 可选。
- stream-json 可解析。
- plan 可展示。
- tool result 可展示。
- result 结束事件可识别。
- stop 能中断。
- 历史回放不重复执行工具。

### 15.4 离线评测集（P1）

提示词和计划策略会反复迭代，没有回归基线就没有质量护栏：

- 维护 golden case 集：每个 case 含用户输入、stub 工具返回、期望行为（是否追问、是否引用证据、根因方向）。
- 基于 `StubLlmClient` 与 stub tool 回放，跑在 `mvn verify`，不依赖真实 LLM。
- 真实 LLM 的端到端评分走 smoke profile，人工触发，输出报告质量评分供 P2 迭代。

---

## 16. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| Plan 过重导致首字延迟增加 | 用户等待变长 | MVP 使用短计划，复杂问题再展开 |
| Evidence 结构占用上下文 | token 增长 | 证据摘要入上下文，原文只保留截断片段 |
| Task 并发导致工具压力 | 影响后端系统 | 全局并发限制、工具级限流 |
| Dubbo 读方法判断不准确 | 可能触发副作用 | 必须改为 allowlist，不依赖方法名前缀 |
| Skill 和 Tool 重复表达 | 维护成本上升 | Skill 只放流程，Tool schema 只放执行契约 |
| 敏感数据进入日志 | 安全风险 | 默认关闭明文日志，统一脱敏 |
| 快照与 history 漂移 | 重建出错误状态 | evidence 只存 toolUseId 引用，对不上即丢弃该证据并降级 |
| LLM 结构化输出不合 schema | plan / report 解析失败 | tool-use 强制 schema、一次重试、NEED_INFO 降级 |
| PlanGuard deny 模式卡死 Agent | 计划质量差时无法推进 | 默认 observe 模式，deny 灰度开启 |

---

## 17. 推荐优先级

P0：

1. 诊断 system prompt 注入。
2. `DiagnosisPlan` 和首轮计划输出（`StructuredOutputTool` tool-use 结构化）。
3. `AgentBudget` 主 Agent 预算上限。
4. `EvidenceLedger`。
5. 状态快照往返（`diagnosis_state` 事件 + `stateSnapshot` 回传）。
6. `LogQueryTool`。
7. 工具脱敏和日志治理。
8. agent-web `NATIVE` 端到端跑通。

P1：

1. 离线评测集（golden case + Stub 回放）。
2. `PlanGuardPolicy` deny 模式硬化。
3. 诊断扩展 stream-json 事件前端展示。
4. `DiagnoseToolFactory` 生产装配。
5. Dubbo / HTTP / Redis / MySQL allowlist。

P2：

1. `Task` 诊断化。
2. 并行 Task 调度。
3. 历史案例召回。
4. 诊断报告质量评分。
5. 成本和延迟对比分析。

---

## 18. 结论

当前引擎底座已经具备运行诊断 Agent 的基础，但“可靠诊断”不能只依赖通用 tool-use loop。下一阶段必须把诊断过程显式建模为：

```text
问题定义 -> 诊断计划 -> 假设验证 -> 证据账本 -> 结构化报告
```

Claude Code 的 `Plan` 和 `Task` 能力值得对标，但不能原样照搬。诊断场景需要的是：

- `Plan` = 诊断计划、假设、检查步骤。
- `Task` = 受控子诊断，验证单个假设。
- `Evidence` = 工具结果和结论之间的强绑定。

落地有一个前置条件：结构化状态必须能跨轮存活（6.8 的快照往返），否则 Plan 和假设状态机每轮归零，以上建模只是单轮装饰。

最终形态应是：PromptPack 提供诊断经验，Java Tool 提供受控执行能力，DiagnosisOrchestrator 在预算和计划约束内把二者编排成可审计、可回放、可验证的线上问题诊断流程。
