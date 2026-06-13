# Agent 底座与专用层分层设计方案

> 面向 `claude-code-langchain4j` 从单体工程演进为"通用 Agent 底座 + 专用 Agent"双层产品的架构设计。专用 Agent 以进程内 jar 方式被宿主服务（首个宿主：agent-web）集成。
>
> 配套文档：`diagnosis-agent-capability-design.md`（诊断 Agent 能力设计，本文是其分层前提）。
>
> @author zhourui(V33215020)
> @since 2026-06-11

---

## 1. 背景与动机

Claude Code 越来越臃肿，包含大量专门方向用不到的功能（文件编辑、代码生成、IDE 集成、富终端 UI）。本项目的定位不是复刻它，而是：

1. 提供一个**基础 Agent 底座**（kernel）：通用 LLM + tool-use 主循环、上下文管理、权限、预算、流式输出。
2. 在底座上构建**专用方向 Agent**：首个是供 agent-web 使用的诊断 Agent，未来可能有其他运维向 Agent。
3. 集成方式为**进程内直接接口**：宿主服务依赖专用层 jar，直接调用 Java 接口，无 CLI 子进程管理、无 RPC 序列化往返。

当前工程与该定位的偏差：

| 偏差 | 影响 |
|---|---|
| 单 Maven 模块，CLI 依赖（JLine）与引擎代码同 jar | agent-web 的 classpath 被迫携带 REPL / 终端依赖 |
| 通用件错位在 `interfaces.engine`（`ContextCompactionService`、`SubAgentTool`、`ClaudeStreamJsonListener`） | 通用能力与诊断门面纠缠，无法被第二个专用 Agent 复用 |
| 诊断能力设计把若干通用能力设计为诊断专用（预算、结构化输出、治理包装链、扩展事件） | 第二个专用 Agent 出现时只能复制粘贴 |
| 组装根只有 `CclcApplication.main`（CLI 视角） | 专用 Agent 没有自己的组装入口，宿主无法干净接入 |

---

## 2. 目标与非目标

### 2.1 目标

1. kernel 与专用层有**物理模块边界**，kernel 不感知任何专用语义（不出现 diagnosis 字样）。
2. 宿主服务只依赖专用层一个 artifact，kernel 传递带入，CLI 完全隔离。
3. 对外稳定 API 收到最小面，其余全部 internal。
4. CLI 降级为 kernel 的调试壳，不随专用层发布。
5. 边界先用 ArchUnit 立规则，模块拆分后置到 API 稳定后。

### 2.2 非目标

- 不做 Server / RPC 化，进程内集成是唯一正式形态。
- 不做 SPI / 插件热加载 / 反射扫描——专用 Agent 的组装显式手写（延续无 Spring / Guice 约定）。
- 不做多 LLM provider 抽象（维持 Anthropic only）。
- 本阶段不拆独立工具包模块（`cclc-tools-ops`），出现第二个运维向 Agent 时再评估。

---

## 3. 目标模块结构

```text
claude-code-langchain4j (parent pom)
├── cclc-kernel             基础层：通用 Agent 底座
│     发布物，无 CLI 依赖，无专用语义
│
├── cclc-agent-diagnosis    专用层：诊断 Agent
│     依赖 cclc-kernel
│     agent-web 只依赖这一个 artifact
│
└── cclc-cli                调试壳：JLine REPL
      依赖 cclc-kernel（调试诊断 Agent 时可加依赖 cclc-agent-diagnosis）
      不对外发布（不进入宿主 classpath）
```

依赖方向约束：

- `cclc-kernel` 不依赖任何兄弟模块。
- `cclc-agent-diagnosis` 只依赖 `cclc-kernel`。
- `cclc-cli` 不被任何模块依赖。
- 未来新增专用 Agent = 新增 `cclc-agent-xxx` 模块，平行于 diagnosis，互不依赖。

---

## 4. 能力归位

### 4.1 kernel（通用底座）

| 能力 | 来源 | 说明 |
|---|---|---|
| `AgentExecutor` 主循环 | 现有 | 含 system prompt 注入（待补） |
| `Conversation` / `ToolInvocation` / `ChatMessage` 等 domain | 现有 | 不变 |
| `Tool` / `ToolRegistry` / `PermissionPolicy` / ports | 现有 | 不变 |
| `ReadOnlyPermissionPolicy` | 现有 | 通用硬约束，非诊断专属 |
| `SubAgentTool`（Task） | 现有，错位在 `interfaces.engine` | 归位 kernel，保持通用 prompt 语义 |
| `ContextCompactionService` | 现有，错位在 `interfaces.engine` | 归位 kernel application 侧 |
| `TruncatingTool` | 现有 | 不变 |
| `ClaudeStreamJsonListener` / `ClaudeStreamJsonWriter` | 现有，在 `interfaces.engine` | 基础 stream-json 归 kernel，增加扩展事件 hook（见 4.3） |
| `AgentBudget` | 新增（原诊断设计 7.4 的 `DiagnosisBudget`） | maxTurns / maxToolCalls / maxInputTokens，任何专用 Agent 都需要 |
| 结构化输出机制 | 新增（原诊断设计 13.2 的专用做法） | 通用 `StructuredOutputTool`：tool_choice 强制 + schema 校验 + 重试降级；schema 由专用层注册 |
| 工具治理包装链 | 新增（原诊断设计 11.2） | Timeout / Audit / Redaction / Truncating 的包装机制；脱敏规则、audit sink 等策略由专用层注入 |
| `CancellationToken` / 会话停止注册 | 现有 | 不变 |
| `SystemPromptComposer` | 现有 | kernel 提供组合机制，专用层提供内容（PromptPack） |

### 4.2 专用层（cclc-agent-diagnosis）

| 能力 | 说明 |
|---|---|
| `domain/diagnosis` 全部聚合 | `DiagnosisCase` / `DiagnosisPlan` / `Evidence` / `EvidenceLedger` 等 |
| `DiagnosisPlanner` / `DiagnosisReporter` / `DiagnosisTaskRunner` | 编排，调用 kernel 的结构化输出机制 |
| `PlanGuardPolicy` | 适配 kernel `PermissionPolicy`，规则本体在 domain |
| `DiagnosisStateCodec` | 状态快照序列化 |
| 诊断工具：`LogQueryTool` / `EsReadTool` / `MysqlReadTool` / `RedisReadTool` / `HttpGetTool` / `DubboInvokeTool` | 经 kernel 治理链包装后注册 |
| PromptPack（`prompts/diagnosis/`） | 诊断 SOP markdown |
| `diagnosis_*` 扩展事件 | 经 kernel 扩展事件 hook 注册 |
| `DiagnoseEngine` / `DefaultDiagnoseEngine` / `DiagnosisOrchestrator` | 专用层对外门面 |
| `DiagnoseEngineBuilder` | 专用层组装根（见第 6 节） |

### 4.3 stream-json 扩展事件 hook

kernel 持有 Claude 兼容的基础事件集（`system` / `text_delta` / `tool_use` / `tool_result` / `user` / `result` / `stream_event`），专用层通过 hook 注入自定义事件：

```java
public interface ExtensionEventEmitter {
    void emit(String type, Map<String, Object> payload);
}
```

- kernel 保证扩展事件不破坏基础事件序，`result` 仍是 turn end 唯一判断。
- `diagnosis_plan` / `diagnosis_evidence` / `diagnosis_need_info` / `diagnosis_state` 全部由专用层定义并经此 hook 输出。

### 4.4 相对诊断能力设计文档的归位变更清单

| 诊断文档章节 | 原设计 | 变更 |
|---|---|---|
| 7.4 `DiagnosisBudget` | 诊断专用 record | 改为 kernel `AgentBudget`，诊断层只配置数值 |
| 13.2 `update_plan` / `submit_report` | 诊断专用内部工具 | 机制下沉 kernel `StructuredOutputTool`，诊断层仅注册两个 schema |
| 11.2 治理包装链 | `infrastructure/diagnosis` | 包装机制归 kernel，`RedactionService` 规则与 `ToolAuditSink` 实现留专用层注入 |
| 9 诊断扩展事件 | 直接改 `ClaudeStreamJsonListener` | 改为经 kernel 扩展事件 hook 注册，listener 本体不感知诊断 |

---

## 5. 包结构与 ArchUnit 规则

### 5.1 过渡期（单模块内）

不做大规模包重命名，以现有四层为 kernel 默认，专用代码全部落在 `*.diagnosis` 子包：

```text
com.anthropic.cclc/
  domain/                  kernel
  domain/diagnosis/        专用层
  application/             kernel
  application/diagnosis/   专用层
  infrastructure/          kernel
  infrastructure/diagnosis/ 专用层
  interfaces/cli/          调试壳
  interfaces/engine/       专用层门面（DiagnoseEngine 等）
```

需要先行搬移的错位通用件（从 `interfaces.engine` 迁出）：

- `ContextCompactionService` → `application/context/`
- `SubAgentTool` → `infrastructure/tools/`
- `ClaudeStreamJsonListener` / `ClaudeStreamJsonWriter` → `infrastructure/streamjson/`（kernel 侧，含扩展 hook）

### 5.2 ArchUnit 规则（加入 `LayeredArchitectureTest`）

```java
// 1. kernel 不得依赖专用层
noClasses().that().resideOutsideOfPackage("..diagnosis..")
        .and().resideOutsideOfPackage("..interfaces..")
        .should().dependOnClassesThat().resideInAPackage("..diagnosis..");

// 2. CLI 不被任何包依赖
noClasses().that().resideOutsideOfPackage("..interfaces.cli..")
        .should().dependOnClassesThat().resideInAPackage("..interfaces.cli..");

// 3. 既有四层依赖方向与无环约束保持不变
```

规则 1 落地后，模块拆分就是纯机械搬移。

### 5.3 模块拆分后的包映射

| 过渡期包 | 目标模块 |
|---|---|
| `domain` / `application` / `infrastructure`（非 diagnosis） | `cclc-kernel` |
| `*.diagnosis` + `interfaces/engine` | `cclc-agent-diagnosis` |
| `interfaces/cli` + `CclcApplication` | `cclc-cli` |

---

## 6. 进程内集成 API

### 6.1 组装根

每个专用 Agent 自带组装根，宿主显式构建（无 Spring，宿主自行包成 `@Bean`）：

```java
DiagnoseEngine engine = DiagnoseEngineBuilder.create()
        .llm(anthropicConfig)                  // api key、model、超时
        .budget(new AgentBudget(20, 50, 400_000))
        .toolBackends(esClient, mysqlClient, redisClient, ...)  // 宿主注入连接配置
        .promptPacks(Path.of("prompts/diagnosis"))
        .redaction(RedactionRules.defaults())
        .auditSink(auditSink)                  // 宿主提供落地实现
        .build();
```

CLI 维护自己的组装（`CclcApplication.main`），与专用 Agent 组装互不影响。

### 6.2 对外稳定面（仅此三样，semver 管理）

1. `DiagnoseEngine`：`runStream(RunRequest, Consumer<String>, IntConsumer)` / `stop(sessionId)` / `isRunning(sessionId)`。
2. `RunRequest`：含 history 与 `stateSnapshot` 字段（快照 `schemaVersion` 独立演进）。
3. 事件契约：stream-json 行格式 + 扩展事件类型清单。

其余类一律 internal：过渡期靠包约定 + ArchUnit，模块拆分后只 export 上述 API 包。

### 6.3 事件双通道

| 通道 | 形态 | 适用 |
|---|---|---|
| stream-json 字符串 | `Consumer<String>`，现状 | agent-web 直透 SSE，零改造 |
| typed listener | `AgentEventListener`（kernel 已有事件回调） | 宿主需要程序化消费事件时，省去 JSON 序列化再解析的往返 |

约束：stream-json 由 typed 事件**单向生成**（writer 是 typed 事件的适配器），保证双通道永不漂移。进程内集成相对 CLI / RPC 的效率优势，由 typed 通道兑现。

### 6.4 线程与并发契约

- `runStream` 阻塞至本轮结束，宿主自行决定调度线程（推荐虚拟线程）。
- 同一 `sessionId` 重入：`isRunning` 为 true 时直接拒绝，由宿主排队。
- `stop(sessionId)` 异步生效，经 `CancellationToken` 在循环边界与流式回调中检查。

---

## 7. 演进路线

| 步骤 | 内容 | 验收 |
|---|---|---|
| Step 0 | DESIGN.md §16 记 dated decision：双层产品形态 + 模块拆分计划 | 决策可追溯 |
| Step 1 | ArchUnit 规则上线 + 错位通用件搬移（5.1 三项），同模块内完成 | `mvn verify` 绿，规则违规红灯 |
| Step 2 | 诊断能力 Phase 1–2（capability 文档）按归位后边界实现：`AgentBudget`、`StructuredOutputTool`、治理链均落 kernel 侧包 | capability 文档各 Phase 验收 |
| Step 3 | API 稳定后拆 Maven 三模块（纯机械搬移） | 三模块独立构建，依赖方向符合第 3 节 |
| Step 4 | agent-web 切换依赖 `cclc-agent-diagnosis`，经 `DiagnoseEngineBuilder` 组装 | 端到端通过，宿主 classpath 无 JLine |

Step 1 必须先于诊断 Phase 1 开发，否则新代码继续在错误边界上堆积。

---

## 8. 对现有文档的修改点（待确认后同步）

### 8.1 diagnosis-agent-capability-design.md

- §4 总体设计图：标出 kernel / 专用层边界。
- §7.4：`DiagnosisBudget` 改为引用 kernel `AgentBudget`。
- §11.2：注明包装机制在 kernel，规则由诊断层注入。
- §9：扩展事件注明经 kernel hook 注册。
- §12.1：包结构对齐本文第 5 节。
- §13.2：`update_plan` / `submit_report` 改为对 kernel `StructuredOutputTool` 的 schema 注册。

### 8.2 DESIGN.md

- §16 新增两条 dated decision：①双层产品形态与模块拆分计划 ②CLI 降级调试壳、进程内 API 为唯一正式集成形态。
- §15 对照表补充：通用件搬移后的新位置。

### 8.3 TASKLIST.md

- 在诊断 Phase 1 任务之前插入 Step 1 任务（ArchUnit 规则 + 三项搬移），标注 `blockedBy` 关系。

---

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 只有一个专用 Agent 时过早抽象 kernel API | 抽象错位，返工 | 不做 SPI / 插件机制，组装显式手写；第二个 Agent 出现前 kernel API 允许 breaking change |
| 包搬移与诊断开发并行冲突 | 合并地狱 | Step 1 独立小步先行，搬移期间冻结 `interfaces.engine` 改动 |
| 双通道事件漂移 | stream-json 与 typed 不一致 | stream-json 由 typed 事件单向生成，禁止双处手写 |
| 模块拆分时机拖延 | JLine 持续泄漏进宿主 | Step 3 以"诊断 Phase 2 完成"为硬触发点，不再等 |

---

## 10. 结论

双层定位不要求推翻现有设计，要求三件事：把通用能力从诊断专用设计中下沉到 kernel、用 ArchUnit 先行固化边界、API 稳定后完成物理模块拆分。完成后，"新增一个专门方向的 Agent"的成本收敛为：新建 `cclc-agent-xxx` 模块 + 领域模型 + 工具注册 + PromptPack + 组装根，kernel 零改动。
