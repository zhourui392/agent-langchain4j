# 引擎宿主集成强化方案（Engine Integration Hardening Plan）

> 在 `diagnose-engine-plan.md`（E0–E8 已完成）基础上，补齐引擎融入 agent-web 诊断服务所缺的引擎侧能力。
>
> @author zhourui(V33215020)
> @since 2026-06-13

---

## 0. 背景与现状

E0–E8 交付了引擎本体：stream-json 序列化（E1）、无状态门面（E2）、只读权限（E3）、六个只读诊断工具（E5）、截断 + auto-compact（E6）、SubAgentTool（E7）、usage 透出（E8 部分）。agent-web 侧已有 `native-diagnose` Maven profile、`NativeDiagnoseGateway` 端口与 `AgentKitNativeDiagnoseGateway` 适配器，单轮诊断可跑通。

对照两侧代码盘点出的缺口（按影响排序）：

| # | 缺口 | 证据 | 影响 |
|---|---|---|---|
| G1 | 终态契约只有 `IntConsumer onExit`，stateSnapshot 仅以 `diagnosis_state` 流事件吐出，宿主须从流里捞 | `DefaultDiagnoseEngine.runStream`、`DiagnosisOrchestrator` 尾部 emit | 多轮诊断的"回传半边"缺失，宿主落库契约脆弱 |
| G2 | 宿主无法把已持久化的 stream-json 行还原成 `List<TurnMessage>` | agent-web 只存原始流文本 | history 传不回来，每轮都是无状态首轮 |
| G3 | Writer 不发整合的 `type:assistant` 行 | `ClaudeStreamJsonWriter` 只有 `stream_event` 增量 | agent-web `StreamOutputExtractor` 按 `type:assistant` 提取正文，native 路径只能靠 `result` 行兜底；G2 反解也无锚点 |
| G4 | 后端装配无配置驱动入口，宿主须手写 6 个 client 组装 | agent-web `NativeDiagnoseConfig` 只配了 llm/budget/promptPacks，**工具一个没注册** | native 引擎在 agent-web 里是"无工具裸 LLM" |
| G5 | 异常吃掉不留堆栈、模块零日志 | `DefaultDiagnoseEngine.runStream` catch `RuntimeException` 只回调 exit code | 服务端排障无据，违背"错误日志必须含完整堆栈"红线 |
| G6 | exit `-1` 同时表示 stop 与 timeout | `DiagnoseEngine` javadoc | 前端 / 诊断历史无法区分用户中止与超时 |
| G7 | `cache_read_input_tokens` 写死 0 | `LangChain4jLlmClient.java:92` TODO | 成本统计失真（prompt cache 是成本大头） |
| G8 | 重复 sessionId 静默覆盖 token；无全局并发上限 | `RunningSessions.register` 直接 `put` | 旧 run 失联不可 stop；服务器场景可被打满 |
| G9 | 引擎无关闭生命周期 | `timeoutScheduler` 无 shutdown 入口 | Spring 容器关停无法 graceful drain |
| G10 | `StructuredDiagnosisPlanner/Reporter` 无便捷装配 | builder 须逐个手配 | 结构化诊断能力在宿主侧闲置 |
| G11 | SNAPSHOT 工件未发布 | `0.1.0-SNAPSHOT`，agent-web profile 默认关 | 集成无法常态化 |

**不在本方案范围**（agent-web 仓库的对接半边，另列任务）：gateway 传入 history/stateSnapshot、`DiagnoseTask` 增 usage 字段、`continueAsChat` 路由回 native、native 路径的 `@SpringBootTest` SSE 集成测试。

---

## 1. 目标与边界

### 1.1 必须实现

- 终态结构化回调 `RunSummary`（退出原因 + 状态快照 + usage + 错误），向后兼容旧 `onExit`。
- stream-json 历史反解器：持久化行 → `List<TurnMessage>`，与 Writer 同仓演进。
- 配置驱动的工具后端工厂：宿主给连接配置即得 `DiagnosisToolBackends`。
- SLF4J 日志 + 异常完整堆栈；stop/timeout 退出语义拆分。
- 引擎 `AutoCloseable` + 并发护栏（重复 sessionId 拒绝、全局并发上限）。
- `cache_read_input_tokens` 真实透出。
- 发布 `0.2.0` 正式版本到内部仓库。

### 1.2 不在范围

- 引擎自带 Spring 装配 / starter（保持纯 Java jar，宿主自己写 `@Configuration`）。
- MCP、写工具、web/SSE（沿用 E 计划边界）。
- agent-web 仓库改动（仅在 §6 列对接清单）。

---

## 2. 关键设计决策

> 编号接续 `diagnose-engine-plan.md` 的 D-1…D-4。

### D-5 终态收口为 `RunSummary`，流事件保留为流式视图

新增回调签名（旧签名保留并默认委托，标 `@Deprecated`）：

```java
public interface DiagnoseEngine extends AutoCloseable {
    void run(RunRequest request, Consumer<String> onChunk, Consumer<RunSummary> onComplete);

    @Deprecated
    default void runStream(RunRequest request, Consumer<String> onChunk, IntConsumer onExit) {
        run(request, onChunk, summary -> onExit.accept(summary.legacyExitCode()));
    }

    void stop(String sessionId);
    boolean isRunning(String sessionId);
    @Override void close();
}

public record RunSummary(ExitReason reason,        // SUCCESS / STOPPED / TIMEOUT / ERROR / REJECTED
                         String stateSnapshot,     // 末次编码的诊断状态，宿主直接落库
                         Usage usage,              // 聚合 token 用量（input/output/cache_read）
                         String errorDetail) {     // ERROR 时的摘要，堆栈走日志
    public int legacyExitCode() { ... }            // SUCCESS=0, STOPPED/TIMEOUT=-1, 其余=1
}
```

**理由**：宿主落库需要的三样东西（快照、usage、终态）目前散落在三个流事件里，靠解析流取数是把序列化格式当 API 用。`diagnosis_state` / `result` 流事件**不删**——SSE 订阅端仍可流式消费，`RunSummary` 是同一数据的终态收口，双轨不冲突。

实现路径：`DiagnosisOrchestrator.run` 返回 `OrchestrationResult(snapshot, usage)`（内部 listener 已聚合 usage、已编码 snapshot，只差返回）；`DefaultDiagnoseEngine` 组装 `RunSummary`。

### D-6 退出语义拆分：超时标记先于 cancel

`-1` 混叠的根因是 timeout 调度器直接调 `cancel::cancel`，与用户 stop 走同一条路。改法：调度器先置 per-run 的 `timedOut` 标记再 cancel；收尾时 `reason = timedOut ? TIMEOUT : (cancel.isCancelled() ? STOPPED : ...)`。不动 `CancellationToken`（domain 不为宿主语义加字段）。

`result` 流事件同步拆分：超时发 `subtype=error_max_duration`，stop 发 `subtype=error_cancelled`（对齐 Claude CLI 既有 subtype 风格，最终以 E0 样本核对为准）。

### D-7 历史反解器归引擎侧，锚点是整合 assistant 行

宿主持久化的就是引擎吐出的 stream-json 行，"行 → `TurnMessage`"的逆映射必须与 Writer 同仓演进，否则两边各写一份迟早漂移。

前置修正（G3）：`ClaudeStreamJsonWriter` 在每个 assistant 轮结束时补发整合行
`{"type":"assistant","message":{"content":[{type:text,...},{type:tool_use,...}]}}`——这是真实 Claude CLI 的行为，agent-web 的 `StreamOutputExtractor` 已按此提取正文，补齐属于契约对齐而非新发明。

反解器随后变得平凡：

```java
public final class StreamJsonHistoryParser {
    /** 只认 type=assistant / user(tool_result) 行，stream_event 增量与 result 行跳过。 */
    public List<TurnMessage> parse(Stream<String> lines);
}
```

放 `agentkit-agent-diagnosis` 的 `interfaces/engine`（与 `ConversationRebuilder` 互逆，相邻放置）。坏行策略：单行解析失败跳过并 WARN，不让历史污染毁掉整轮诊断；tool_use/tool_result 配对断裂时丢弃孤儿侧，保 `ToolUseInvariantChecker` 不破。

### D-8 后端工厂：配置 record 进，`DiagnosisToolBackends` 出

```java
public record DiagnosisBackendConfig(EsConfig es, MysqlConfig mysql, RedisConfig redis,
                                     LogQueryConfig logQuery, HttpConfig http, DubboConfig dubbo) {
    // 子配置均可为 null = 不启用该后端
}

public final class DiagnosisToolBackendsFactory {
    public static DiagnosisToolBackends fromConfig(DiagnosisBackendConfig config);
}
```

工厂内部装配既有实现：`HttpEsReadClient` / `JdbcMysqlReadClient` / `SocketRedisClient` / `JdkHttpReader` / `SocketDubboTelnetClient` / LogQuery 客户端。缺哪个子配置就不装哪个后端（`DiagnoseToolFactory` 已支持按 backends 存在性注册工具）。宿主侧从"手写 6 个 client"降为"绑一个配置 record"。

凭证只走构造参数，工厂不读环境变量、不留默认密码——配置来源是宿主的事。

### D-9 并发护栏：重复拒绝 + 信号量上限

- `RunningSessions.register` 改为 `putIfAbsent`；已在跑同 sessionId → 立即 `onComplete(REJECTED)`，不发 LLM 请求。静默覆盖是事故源：旧 run 的 token 失联后 `stop` 永远打不中它。
- `EngineOptions` 增 `maxConcurrentRuns`（默认不限）；超限同样 `REJECTED` 快速失败，不排队——排队语义归宿主的任务队列，引擎不越俎。

### D-10 生命周期：`close()` 取消在跑 run 并有界等待

`close()` 顺序：拒绝新 run → cancel 所有在跑 token → 有界等待 drain（默认 10s）→ shutdown `timeoutScheduler`。宿主侧 `@Bean(destroyMethod = "close")` 即接上 Spring 容器关停。幂等，重复调用无害。

### D-11 日志：引擎是服务端组件，按服务端标准打日志

`agentkit-agent-diagnosis` 加 SLF4J（api 已是传递依赖）。固定日志点：

| 时机 | 级别 | 内容 |
|---|---|---|
| run 开始 | INFO | sessionId、workingDir、history 条数、是否带 snapshot |
| run 结束 | INFO | sessionId、reason、耗时、usage 三项 |
| 异常 | ERROR | sessionId + **完整堆栈**（修复 `runStream` 吞异常） |
| 权限 DENY / 治理拦截 | WARN | sessionId、工具名、拒绝原因 |

不引 MDC、不引 metrics 门面——指标聚合宿主基于 `RunSummary` 自己做，引擎不替宿主选型。

### D-12 cache_read_input_tokens：核 langchain4j 1.8.0 API

`LangChain4jLlmClient.java:92` 的 TODO。langchain4j 的 Anthropic 模块在响应里带 `AnthropicTokenUsage`（含 `cacheReadInputTokens`），实现时先核 1.8.0 实际类型：能取则直接透传；取不到（被抹平成通用 `TokenUsage`）则评估升级 langchain4j 小版本，仍不行才考虑自定义响应解析。**禁止估算值**——拿不到就保持 0 并在 javadoc 注明，失真的数据比缺失更糟。

---

## 3. 阶段与任务分解

> 风格沿用 E 计划：TDD 任务 Red → Green → Refactor 三次提交；函数 ≤50 行、嵌套 ≤3 层；`blockedBy` 标依赖。测试复用 `StubLlmClient` / `FakeTool`，不连真实外部系统。

### 阶段 F1：终态契约闭环（最高优先）

#### #F1-1 [TDD] 整合 assistant 行（G3，blockedBy: 无）

**Red**：`ClaudeStreamJsonWriterTest` 增
- `writesConsolidatedAssistantMessage` — text + tool_use 两 block 的整合行字段对齐 E0 样本
- `ClaudeStreamJsonListenerTest` 增 `emitsAssistantLineAfterContentBlocks` — 增量行之后、tool_result 之前出现整合行

**Green**：Listener 累积本轮 content block，轮末调 `writer.assistantMessage(blocks)`。

**Refactor**：block 累积抽 `ContentBlockBuffer`。

**DoD**：测试绿；用 agent-web `StreamOutputExtractor` 的提取逻辑（复制其样本到测试资源）验证正文可提取。

#### #F1-2 [TDD] OrchestrationResult + RunSummary（G1/G6，blockedBy: F1-1）

**Red**：`DefaultDiagnoseEngineTest` 增
- `completesWithSummaryCarryingSnapshotAndUsage` — 终态回调含编码快照与聚合 usage
- `distinguishesTimeoutFromStop` — 超时 → `TIMEOUT`，`stop()` → `STOPPED`
- `legacyOnExitStillReceivesExitCode` — 旧签名兼容
- `errorSummaryCarriesDetailAndLogsStack`（配 logback 测试 appender 断言堆栈落日志）

**Green**：`DiagnosisOrchestrator.run` 返回 `OrchestrationResult`；超时标记先于 cancel；`DefaultDiagnoseEngine` 组装 `RunSummary`，catch 处补 `log.error(..., ex)`。

**Refactor**：reason 判定抽 `ExitReasonResolver` 纯函数。

**DoD**：4 测试绿；`DiagnoseEngine` 新旧签名共存，旧签名 `@Deprecated`。

#### #F1-3 [TDD] StreamJsonHistoryParser（G2，blockedBy: F1-1）

**Red**：`StreamJsonHistoryParserTest`
- `parsesAssistantTextAndToolUseTurns`
- `parsesToolResultUserTurns`
- `skipsStreamEventAndResultLines`
- `skipsMalformedLineAndKeepsRest`
- `dropsOrphanToolUseToPreservePairing`
- `roundTripsThroughConversationRebuilder` — parse 产物喂 `ConversationRebuilder` 过 `ToolUseInvariantChecker`

**Green**：jackson 逐行解析，按 D-7 策略组装 `TurnMessage`。

**Refactor**：行类型判定抽 sealed 解析结果，pattern matching 消 if 链。

**DoD**：6 测试绿；与 Writer 的往返测试（Writer 产出 → Parser 反解 → 语义等价）纳入回归。

---

### 阶段 F2：工具后端工厂

#### #F2-1 [TDD] DiagnosisBackendConfig + Factory（G4，blockedBy: 无）

**Red**：`DiagnosisToolBackendsFactoryTest`
- `wiresAllBackendsFromFullConfig` — 6 个后端全配 → 6 工具可注册
- `skipsAbsentBackends` — 只配 mysql → 仅 mysql 工具
- `rejectsBlankCredentialFields` — 必填项空白快速失败（构造期校验）

**Green**：工厂按子配置非空逐个装配既有 client 实现。

**Refactor**：各子配置的校验抽到子 record 紧凑构造器。

**DoD**：3 测试绿；`DiagnoseEngineBuilder` 增 `backendConfig(DiagnosisBackendConfig)` 便捷入口（内部走 `toolBackends`）。

---

### 阶段 F3：并发护栏与生命周期

#### #F3-1 [TDD] 重复拒绝 + 并发上限（G8，blockedBy: F1-2）

**Red**：`DefaultDiagnoseEngineTest` 增
- `rejectsDuplicateSessionIdWhileRunning` — 第二次提交同 id → `REJECTED`，LLM 调用次数不变
- `rejectsBeyondMaxConcurrentRuns` — 上限 1 时第二个 run 立即 `REJECTED`
- `allowsSameSessionIdAfterCompletion`

**Green**：`RunningSessions.register` 改 `putIfAbsent`；`EngineOptions.maxConcurrentRuns` + `Semaphore.tryAcquire`。

**DoD**：3 测试绿；REJECTED 不产生任何 LLM 流量。

#### #F3-2 [TDD] AutoCloseable（G9，blockedBy: F3-1）

**Red**：`DefaultDiagnoseEngineTest` 增
- `closeCancelsInFlightRunsAndDrains` — close 后在跑 run 以 `STOPPED` 收尾
- `closeRejectsNewRuns`
- `closeIsIdempotent`

**Green**：按 D-10 顺序实现；drain 等待用 `CountDownLatch` 族，不忙等。

**DoD**：3 测试绿；javadoc 注明宿主 `destroyMethod` 用法。

---

### 阶段 F4：可观测补全

#### #F4-1 [Infra] 日志点铺设（G5，blockedBy: F1-2）

按 D-11 表逐点落 `log.info/warn/error`；异常堆栈断言已在 F1-2 覆盖，其余日志点不单独写测试（纯委托豁免）。

**DoD**：四类日志点齐；`mvn verify` 绿。

#### #F4-2 [TDD] cache_read_input_tokens（G7/D-12，blockedBy: 无）

**Red**：`LangChain4jLlmClientTest`（或既有 usage 测试）增 `surfacesCacheReadTokensFromAnthropicUsage`（stub Anthropic usage 类型）。

**Green**：按 D-12 路径透传。

**DoD**：测试绿；`UsageReportingTest` 的 result 事件断言补 `cache_read_input_tokens` 非零路径。

---

### 阶段 F5：默认装配与发布

#### #F5-1 [TDD] 结构化诊断便捷装配（G10，blockedBy: F1-2）

`DiagnoseEngineBuilder` 增 `structuredDiagnosis()`：一次装上 `StructuredDiagnosisPlanner` + `StructuredDiagnosisReporter`（复用已配 llm）。不改默认行为——显式开启，避免老调用方静默多出 plan/report 事件。

**Red**：`DiagnoseEngineBuilderTest` 增 `structuredDiagnosisWiresPlannerAndReporter`、`structuredDiagnosisRequiresLlmFirst`。

**DoD**：2 测试绿。

#### #F5-2 [Infra] 发布 0.2.0（G11，blockedBy: F1–F4 全绿）

- 版本 `0.1.0-SNAPSHOT` → `0.2.0`，`mvn deploy` 至内部仓库（无内部仓库则先 `mvn install` + agent-web 锁定坐标）。
- `mvn dependency:tree` 复核与 Spring Boot 3.3 BOM 无冲突（jackson 2.18.2 已对齐）。
- 打 tag `v0.2.0-engine-hardening`；DESIGN.md §16 追加本方案决策（D-5…D-12）。

**DoD**：agent-web `-Pnative-diagnose` 构建以正式坐标通过。

---

## 4. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 整合 assistant 行字段与真实 CLI 样本有出入 | 前端渲染 / 提取回归 | F1-1 以 `docs/samples` 实测样本 + agent-web `StreamOutputExtractor` 双重对齐 |
| 反解器与 Writer 演进脱节 | history 重建漂移 | F1-3 的 Writer↔Parser 往返测试纳入回归，谁改格式谁先红 |
| langchain4j 1.8.0 取不到 cache_read | usage 持续失真 | D-12 三级路径；最坏保 0 并 javadoc 注明，不估算 |
| 接口演进破坏 agent-web 既有适配器 | 集成回退 | 旧 `runStream` 签名保留委托；0.2.0 发布说明列明迁移项 |
| `close()` drain 卡死（工具阻塞不响应中断） | 容器关停挂起 | drain 有界（10s）超时后强制 shutdown 并 WARN |

---

## 5. 验收（Definition of Done）

1. **契约闭环**（F1）：`RunSummary` 携带 snapshot/usage/reason；stop 与 timeout 可区分；持久化流可反解为 history 并通过配对不变式。
2. **开箱可用**（F2/F5-1）：宿主一个配置 record 装齐工具后端；`structuredDiagnosis()` 一键开结构化诊断。
3. **服务端可运营**（F3/F4）：重复提交与超限快速拒绝；容器关停 graceful；异常有完整堆栈；usage 三项齐。
4. **质量门禁**：`mvn clean verify` 绿，`LayeredArchitectureTest` 不破，JaCoCo 不降；TDD 任务三次提交。
5. **发布**：`0.2.0` 正式工件可被 agent-web 以默认仓库解析。

---

## 6. agent-web 侧对接清单（本方案不实施，引擎 0.2.0 就绪后启动）

| 项 | 改动点 | 依赖引擎能力 |
|---|---|---|
| 传入 history + snapshot | `AgentKitNativeDiagnoseGateway.toRunRequest` 补两个 builder 调用；任务续跑前用 `StreamJsonHistoryParser` 反解已存流 | F1-3 |
| 落库 RunSummary | `NativeDiagnoseGateway` 端口签名升级；`DiagnoseTask` 增 usage / exit reason 字段 | F1-2 |
| 工具后端配置 | `NativeDiagnoseConfig` 绑 `agent.native-diagnose.backends.*` → `DiagnosisBackendConfig` | F2-1 |
| continueAsChat 回 native | `DiagnoseAppServiceImpl.continueAsChat` 按来源 agentType 路由 | F1-3 |
| 集成测试 | native 版 `DiagnoseFlowTest`（`StubLlmClient` + SSE 断言）；test profile 启用 native-diagnose | F5-2 |

---

## 7. 执行顺序

```
F1-1 ┬→ F1-2 ┬→ F3-1 → F3-2
     └→ F1-3 ├→ F4-1
             └→ F5-1
F2-1（独立，可并行）
F4-2（独立，可并行）
全绿 → F5-2（发布）→ §6 agent-web 对接
```

关键路径：**F1-1 → F1-2 → F1-3**（契约闭环），其余并行。
