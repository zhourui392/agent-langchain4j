# StructuredAgent 提取到 kernel —— TDD 草图

> 状态：历史 TDD 草图；`StructuredAgent` 已实现，并在 S10 #47 统一改由 domain `AgentSpec` 描述角色。
> 下文保留最初提取过程，当前 API 与正式决策以 `DESIGN.md §16.15` 和生产代码为准。
> 配套原则见 `AGENTS.md` → "kernel 为基座 · agent 逐个扩展"。正式采纳后决策正本进 `DESIGN.md §16`（带日期）。

## 1. 背景与动机

每个"专家 Agent"角色现在都重复同一段四步样板。以 `StructuredDiagnosisPlanner.createPlan` 为证：

```java
AtomicReference<Map<String, Object>> acceptedPlan = new AtomicReference<>();
ToolRegistry tools = new ToolRegistry().register(new StructuredOutputTool(   // ① 建终结工具
        TOOL_NAME, "Submit a structured diagnosis plan", PLAN_SCHEMA, acceptedPlan::set));
Conversation conversation = new Conversation(SessionId.fresh());             // ② 起会话
conversation.append(UserMessage.of("Create a diagnosis plan for: " + diagnosisCase.question()));
new AgentExecutor(llm, tools).run(conversation, new CancellationToken(),     // ③ 跑 executor
        AgentEventListener.NO_OP, SYSTEM_PROMPT).join();
DiagnosisPlan plan = toPlan(acceptedPlan.get());                             // ④ 读 sink → 转 VO
```

①②③ 对任何角色都一模一样（Planner / Coder / Reviewer / Reporter），只有 schema、systemPrompt、领域工具、④ 的 VO 映射不同。这段该被 kernel 收掉。

## 2. 落点与分层依据

落在 **`agentkit-kernel` 的 `infrastructure/agent/`**，不在 application。依据：

- `StructuredAgent` 要同时引用 `AgentExecutor`（application）与 `StructuredOutputTool`（infrastructure）。
- ArchUnit `applicationHasNoInfrastructureDependency` 禁止 application → infrastructure，故不能放 application。
- `infrastructure → application` 允许，且 `SubAgentTool`（同在 `infrastructure/tools/`）已是先例——它在 infra 里 import 了 `application.AgentExecutor`。
- 返回**通用 payload（`Map<String,Object>`）**，不认识任何领域 VO，故不触发 `kernelHasNoDiagnosisDependency`。payload→VO 映射留在各 agent 包。

## 3. 目标 API

```java
package com.anthropic.agentkit.infrastructure.agent;

/** 终结工具规格 —— 角色"唯一收尾方式"的物化。 */
public record TerminalToolSpec(String name, String description, String schema) { /* 非空校验 */ }

/** 终结工具从未被调用 —— 专家 Agent 未产出结构化结果。 */
public final class StructuredOutputMissingException extends RuntimeException { /* msg 含 toolName */ }

/**
 * 让一个受约束的专家 Agent 跑一轮并产出结构化 payload。
 * kernel 只返回通用 payload；payload→领域 VO 的映射由调用方（agent 包）负责。
 */
public final class StructuredAgent {
    public StructuredAgent(LlmClient llm, String systemPrompt,
                           TerminalToolSpec output, List<Tool> domainTools);   // 4 参，≤5

    public Map<String, Object> run(String task, ExecutionContext ctx);
}
```

设计要点：
- 构造参数 `(systemPrompt, domainTools, output)` 即"角色"的物化（对齐 AGENTS.md 多角色原则里的 `Role`）。Planner 的 `domainTools` 为空；Coder 会带读写工具 + 自己的终结工具。
- `run` 取 `ExecutionContext`，用 `ctx.cancellation()` 而非 `new CancellationToken()`——顺手补齐"承重缝"里的取消透传。
- 终结工具未被调用即 `throw StructuredOutputMissingException`，把"agent 没产出"的失败集中到一处（现在散落在各 `toPlan` 的 null 判断里）。

> 注：当前 `StructuredOutputTool` 不会主动中断循环——sink 在一次普通 tool 调用里被填充，循环靠 LLM 后续不再发起 tool 调用而自然结束。本次提取**保持这一现状**，不改循环语义。"终结工具触发即结束循环"是更激进的演进，另开任务。

## 4. TDD 步骤

### Red —— `infrastructure/agent/StructuredAgentTest.java`

复用 `testsupport/StubLlmClient`。注意 stub 要 enqueue 两条：第一条发起终结工具调用，第二条无 tool 调用以结束循环。

```java
private static final String SCHEMA =
        "{\"type\":\"object\",\"properties\":{\"problemStatement\":{\"type\":\"string\"}},"
                + "\"required\":[\"problemStatement\"]}";

@Test
void emitsPayloadWhenAgentCallsTerminalTool() {
    StubLlmClient llm = new StubLlmClient()
            .enqueue(AiMessage.of("", List.of(new ToolUseRequest(
                    new ToolUseId("t-1"), "submit_plan", "{\"problemStatement\":\"db slow\"}"))))
            .enqueue(AiMessage.text("done"));                       // 结束循环
    StructuredAgent agent = new StructuredAgent(llm, "Plan it.",
            new TerminalToolSpec("submit_plan", "Submit a plan", SCHEMA), List.of());

    Map<String, Object> payload = agent.run("Make a plan", ExecutionContext.at(Path.of(".")));

    assertThat(payload).containsEntry("problemStatement", "db slow");
}

@Test
void throwsWhenTerminalToolNeverCalled() {
    StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("I won't"));
    StructuredAgent agent = new StructuredAgent(llm, "Plan it.",
            new TerminalToolSpec("submit_plan", "Submit a plan", SCHEMA), List.of());

    assertThatThrownBy(() -> agent.run("Make a plan", ExecutionContext.at(Path.of("."))))
            .isInstanceOf(StructuredOutputMissingException.class)
            .hasMessageContaining("submit_plan");
}
```

可选第三条（验角色能用领域工具）：注入一个 `FakeTool` 作 `domainTools`，脚本让 agent 先调它再调终结工具，断言 `FakeTool` 被调用——证明 `domainTools` 真正挂进了子 registry。

### Green —— `infrastructure/agent/StructuredAgent.java`

```java
public Map<String, Object> run(String task, ExecutionContext ctx) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(ctx, "ctx");
    AtomicReference<Map<String, Object>> sink = new AtomicReference<>();
    Conversation conversation = new Conversation(SessionId.fresh());
    conversation.append(UserMessage.of(task));
    new AgentExecutor(llm, buildRegistry(sink))
            .run(conversation, ctx.cancellation(), AgentEventListener.NO_OP, systemPrompt)
            .join();
    Map<String, Object> payload = sink.get();
    if (payload == null) {
        throw new StructuredOutputMissingException(output.name());
    }
    return payload;
}

private ToolRegistry buildRegistry(AtomicReference<Map<String, Object>> sink) {
    ToolRegistry registry = new ToolRegistry();
    domainTools.forEach(registry::register);
    registry.register(new StructuredOutputTool(
            output.name(), output.description(), output.schema(), sink::set));
    return registry;
}
```

`run` 含一个 null 判断分支，落在 infrastructure——按门禁这是"协议适配"层的产出校验（终结工具是否产出），不是业务规则，允许留此层；业务判断（plan 是否合法）仍在领域 VO。方法 ≤50 行、嵌套 ≤3，达标。

### Refactor —— 让 `StructuredDiagnosisPlanner` 委托

保持现有 `DiagnosisPlannerTest` / 诊断流程绿灯不变（回归安全网）。`createPlan` 塌缩为：

```java
@Override
public DiagnosisPlan createPlan(DiagnosisCase diagnosisCase) {
    StructuredAgent agent = new StructuredAgent(llm, SYSTEM_PROMPT,
            new TerminalToolSpec(TOOL_NAME, "Submit a structured diagnosis plan", PLAN_SCHEMA),
            List.of());
    Map<String, Object> payload = agent.run(
            "Create a diagnosis plan for: " + diagnosisCase.question(),
            ExecutionContext.at(Path.of(".")));
    return toPlan(payload);                 // toPlan 里的 null 分支可删（已由 StructuredAgent 兜住）
}
```

净删除：`AtomicReference` / 手建 `ToolRegistry` / `Conversation` 拼装 / `AgentExecutor` 调用 / `toPlan` 的 null 判断。`StructuredDiagnosisPlanner` 退化成"配置 + 一行 payload→VO 映射"。

## 5. 验收

```powershell
mvn -pl agentkit-kernel -Dtest=StructuredAgentTest test          # 新单测绿
mvn -pl agentkit-agent-diagnosis -Dtest=*PlannerTest test        # 诊断回归绿
mvn -pl agentkit-kernel -Dtest=LayeredArchitectureTest test      # 分层不破
mvn clean verify                                                 # 全量 + ArchUnit + jacoco
```

DoD：
- `StructuredAgentTest` 两条核心用例绿。
- 诊断现有测试全绿（行为不变，仅结构重构）。
- ArchUnit 全绿（`StructuredAgent` 在 infra，未引入 application→infra 或 kernel→diagnosis）。
- Red / Green / Refactor 三次独立提交，不批量。

## 6. 边界与后续

- **本次不做**：不改 `StructuredOutputTool` 的"终结即结束循环"语义；不引入 `Role` 独立 record（`StructuredAgent` 构造参数已物化角色，是否再抽 `Role` 看角色数量增长再定）；不做 `AgentManifest`。
- **后续触点**：第二个 agent 包（coding）落地时，`Planner`/`Coder`/`Reviewer` 直接复用 `StructuredAgent`，验证它是否真的领域无关；若 coding 的 `Coder` 需要"边改边产出"，再评估循环语义演进。
- **承重缝衔接**：`StructuredAgent.run(task, ctx)` 已吃 `ExecutionContext`，未来 `ExecutionContext` 长出 `WorkspaceId`/`ProjectId` 时，本类无需改签名即随之透传。
