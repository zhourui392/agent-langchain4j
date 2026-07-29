# 项目说明
## 这个项目是什么

Java 21 + LangChain4j 实现的 CLI Agent 核心循环：消息轮次、工具调用、上下文管理、权限、流式输出。

两份活文档驱动开发：
- `DESIGN.md` —— 架构、抽象、已定的取舍（§16 记录带日期的决策）。
- `TASKLIST.md` —— S0–S8 + MVP-Gate 共 39 个编号任务，含明确的 Red/Green/Refactor 步骤与 `blockedBy` 依赖。**以任务 ID 为工作单元**——接新活时，找依赖已满足、编号最小的未完成任务。

## 命令

需要 Maven 3.9+ 和 JDK 21。默认 OpenAI 兼容 provider 设置 `AK_API_KEY` 或 `OPENAI_API_KEY`；用 Anthropic 则设 `AK_PROVIDER=anthropic` 加 `ANTHROPIC_API_KEY`。

```powershell
mvn clean verify                                  # compile + unit tests + failsafe IT + jacoco
mvn test                                          # unit tests only (surefire)
mvn -Dtest=ConversationTest test                  # single test class
mvn -Dtest=ConversationTest#appendsMessagesInOrder test   # single test method
mvn verify -Psmoke                                # runs *SmokeIT.java (needs provider API key; skipped in CI)
mvn exec:java                                     # launch AgentKitApplication REPL
mvn exec:java -Dexec.args="--version"             # CLI flags
```

`verify` 后 JaCoCo 报告生成在 `target/site/jacoco/index.html`。CI 在 push/PR 时跑 `mvn -B -ntp clean verify`（`.github/workflows/ci.yml`）。

## 架构：DDD 四层，ArchUnit 强制

```
interfaces/cli  →  application  →  domain  ←  infrastructure
```

依赖方向由 `src/test/java/com/anthropic/agentkit/arch/LayeredArchitectureTest.java` 强制。违反则构建失败。特别是：

- **Domain 禁止 import LangChain4j、infrastructure、application 或 interfaces。** 所有与外部世界的耦合都走 `domain/port/` 里的 port（`LlmClient`、`ChatMemoryStore`）。
- **Application 禁止 import infrastructure 或 interfaces。** 它通过 port 编排 domain；具体绑定在 `AgentKitApplication.main` 发生。
- 整个包图必须无环（`noCyclesInLayers`）。

各层职责（对应 `DESIGN.md` §2）：

- **interfaces/cli** —— JLine REPL（`ReplLoop`）、`SlashCommandParser`、`OutputRenderer`、`SigintHandler`。终端 I/O、slash 命令分发、流式渲染。
- **application** —— `AgentExecutor`（核心——主轮次循环）、`PermissionService`、`SystemPromptComposer`、`SessionResumer`、`ParallelToolDispatcher`、`InvocationFactory`。用例编排；**无业务规则**、**无 infra 依赖**。
- **domain** —— 聚合 `Conversation` / `ToolInvocation`、sealed `ChatMessage` 层级、`Tool` 接口、`PermissionPolicy`、port `LlmClient` / `ChatMemoryStore`。纯粹，零外部依赖。
- **infrastructure** —— `LangChain4jLlmClient`（Anthropic 流式）、工具实现（`BashTool`、`FileEditTool`、`GlobTool`、`GrepTool`……）、`FileChatMemoryStore`（JSONL）、context provider、`DefaultPermissionPolicy`。实现 domain port。

组合根：`AgentKitApplication.main` 手工 wiring 一切——无 Spring/Guice。新组件必须加进那里的 wiring。

## Agent 主循环

`application.AgentExecutor.run(conversation, cancel)` 是主轮次循环。每一轮：

1. 通过 `SystemPromptComposer` 组装 system prompt（稳定前缀供 prompt cache）。
2. 通过 `LlmClient` port 流式获取 LLM 响应——部分 token 走 `StreamHandler.onPartialText`。
3. 把 assistant `AiMessage` append 进 conversation。若无 `toolUseRequests` → break。
4. 每个工具调用在执行前都过 `PermissionService.check`（ALLOW/ASK/DENY）。
5. 工具经 `ParallelToolDispatcher`（虚拟线程）分发，但 `ToolResultMessage` 按**原始 `tool_use` 顺序** append——这条不变量没有商量余地（Anthropic API 要求配对顺序）。
6. `CancellationToken` 在每次循环开始和流式 handler 内部都检查；取消时循环优雅收尾。

## 不显眼的不变量

- **Conversation 配对**：`tool_use` 和 `tool_result` 必须配对。`ToolUseInvariantChecker` 在每次 `Conversation.append` 时强制。加新消息流前先测。
- **FileStateCache 先读后写**：`FileEditTool` 和 `FileWriteTool`（对已存在文件）拒绝操作，除非该文件在本 session 内先被 `FileReadTool` 读过。缓存还跟踪 `mtime` 以检测外部修改。绕过这道保护会破坏先读后写保证。
- **Prompt cache 断点**：`SystemPromptComposer` 在动态上下文前产出稳定前缀（`SYSTEM_INSTRUCTIONS` → AGENTS.md → 工具描述）。`CacheBreakpointStrategy`（在 infra `llm/`）插入 ephemeral cache 标记。别把动态内容（日期、git status）塞进前缀——会毁掉缓存命中率。
- **Shell 选择**：`BashTool` 委托给 `ShellSelector`，Windows 选 `cmd.exe`，其它平台选 `bash -c`。测试必须跨平台跑。
- **Grep 后端**：`GrepTool` 在启动时 `rg --version` 成功则用 ripgrep，否则回退到 `JavaRegexGrepBackend`。两个后端都有测试。
- **Session 存储**：`FileChatMemoryStore` 把 JSONL 写到 `~/.agentkit/sessions/<id>.jsonl`（路径经 `SessionPaths`）。`/resume <id>` 重载消息历史**但不重跑工具调用**——`tool_use` 和 `tool_result` 仅作数据持久化。

## TDD 纪律（项目规则，非可选）

测试是交付资产。`TASKLIST.md` 里每个标 `[TDD]` 的任务必须按 Red → Green → Refactor 分三次提交——不批量、不直接跳到实现。方法长度上限 **50 行**，嵌套上限 **3 层**（见全局编码规范）。方法逼近上限时抽私有 helper（先例：`AgentExecutor.run` 抽出 `executeTurn` / `dispatchToolCalls`）。

`StubLlmClient` 和 `FakeTool`（在 `src/test/java/.../testsupport/`）是不碰真实 API 单测 executor 的标准接缝。复用它们，别引入新 mock。

## 明确不做的（不要加）

按 `DESIGN.md` §1.3 和 §14.2：无 Spring/Guice、无 Lombok、无 Ink 式富终端 UI、无多模态输入（MVP 仅文本）、无 IDE 桥接/插件子系统。多 provider 支持仅限 `DESIGN.md` §16.6 记录的 OpenAI/Anthropic 工厂。Skill 支持按 `DESIGN.md` §16.4 仅知识层；别通过 skill 加脚本执行或自动启用 Bash。配置是环境变量 + `~/.agentkit/config.json`——无注解、无基于反射的 DI。

## 多角色协作演进原则（规划，未落地）

> 面向 "Devin 式多角色协作开发" 的方向性原则。**当前代码未实现**，记录于此是为了在写新组件时守住演进路径。若正式采纳，决策正本应进 `DESIGN.md §16`（带日期），本节只作工作纪律提醒。

### 拓扑与子 Agent 抽象
- 编排拓扑选 **主从 + 串行流水线**（Orchestrator 拆任务，Planner → Coder → Reviewer 串行验收），不选 peer 黑板自由协作（易发散、token 爆、无清晰验收点）。
- **子 Agent = 注入 `Role` 的 `AgentExecutor`**，不是子类。`Role` 是 VO：`(systemPromptTemplate, allowedTools, modelTier, terminalTool)`。能力约束落在 `allowedTools` 白名单这种硬边界（Reviewer 拿不到写工具），**不靠 prompt 求 LLM 自觉**。
- **交接走终结工具（Terminal Tool），不走自然语言**。每个角色唯一的收尾方式是调用一个终结工具，其 schema 即交接 VO（`submit_plan`/`submit_patch`/`submit_review`）。`AgentExecutor` 检测到终结工具被调用即结束循环，拿到的是强类型参数，零文本解析。复用现有 `Tool` 框架与 `ToolUseInvariantChecker`，`StubLlmClient` 可纯单测。
- 重试/转人工判定是 `Task` 聚合根的不变量，**禁止在 `Pipeline` 里写 `if (retryCount < 3)`**（App 层代劳，必须下沉）。

### 隔离分层（三层，性质不同）
- **User ↔ User**：安全隔离（数据/密钥/代码不泄露），**硬隔离**，必须容器/VM + 鉴权。
- **Project ↔ Project**：独立 repo + 独立 secret 作用域，中等隔离。
- **Requirement ↔ Requirement**：正确性隔离（不串台），**软隔离**，git worktree（一需求一 worktree，共享 `.git`，开发期隔离、合并期暴露冲突）。
- 注意：worktree 只在**单用户**前提下够用。一旦多用户，结论翻转——同进程同文件系统下 Bash 工具可读他人 worktree，必须升级到容器沙箱。

### 五条承重缝（现在写新组件就要守，否则 L2 多租户要返工）
核心纪律一句话：**凡是会碰外部世界/写状态的东西，作用域 id 必须显式传入，绝不从全局单例隐式获取。**
1. **外部状态 port 的签名带 scope id**：`load(WorkspaceId)` 而非 `load()`；L1 扩成 `load(ProjectId, WorkspaceId)`。现在用 id 当文件名前缀，L2 换成 DB 主键列，调用方不动。
2. **工具执行工作目录显式传入**，禁止依赖进程 CWD / `user.dir`。现在传 worktree 路径，L2 传容器挂载路径，约定不变。
3. **密钥/配置经 `SecretProvider` port 获取**，禁止工具实现直接 `System.getenv`。现在读 env，L2 按 `(userId, projectId)` 从密钥库取。多租户最易泄露点。
4. **`AgentExecutor` 无状态可重入**，状态全在传入的 `Conversation`/`Workspace`，不持有跨调用实例字段。保证一个 Worker 能并发跑多需求且水平扩容。
5. **引入会长大的 `ExecutionContext`**，从入口一路透传到工具。L0 只含 `WorkspaceId`，L1 加 `ProjectId`，L2 加 `UserId/TenantId`。链路现在铺好，L2 不必全链路加参数。

### 演进阶段与现在不做清单
- **L0（当前目标）**：单用户单项目，并行需求用 worktree 隔离。
- **L1**：单用户多项目，加项目级 worktree 命名空间 + 项目级 secret 作用域，基本仍单进程。
- **L2（拐点）**：多用户，必须上容器沙箱 + 控制面/执行面拆分 + 数据库状态 + 鉴权。这是重写外壳，不是加功能；但虚线以下（`Role`/终结工具/worktree/`Pipeline`/`AgentExecutor`）原样复用。
- **现在不要做**：容器/沙箱、Spring/DI、调度器/队列、为多租户提前抽数据库。守住五条缝即可把 L2 的门开着，提前做是纯负担。

## kernel 为基座 · agent 逐个扩展（扩展方向）

> 扩展方向原则。`agentkit-kernel` 是基座（agent 运行时 + SPI），每个 agent 是 kernel 之上的一个**包**。`agentkit-agent-diagnosis` 是第一个 agent 包；"Devin 式多角色协作开发" 不是独立产品，而是**另一个平级的 agent 包**（会编排子 Agent 的 coding agent），复用同一套 kernel。

### 职责边界
- **kernel 提供（领域无关、稳定）**：`AgentExecutor` 回合循环、`ParallelToolDispatcher`；扩展点 SPI —— `Tool`、`SubAgentTool`（起隔离子 Agent）、`StructuredOutputTool`（终结工具/结构化交接）、`ContextProvider`、`PermissionPolicy`；`AgentBudget`（配额）、`governance/`（审计/脱敏）。
- **每个 agent 包提供（领域特定）**：领域工具（`DubboInvokeTool`/`EsReadTool`…）、终结工具 schema、领域 VO（交接载体，如 `DiagnosisPlan`，未来 `Patch`/`ReviewVerdict`）、payload→VO 映射、**自己的 orchestrator**。
- **编排不下沉 kernel**：诊断循环（假设→取证→更新计划）与写码循环（拆任务→改→评审→打回）是不同领域工作流 = 业务规则，按分层纪律留在各包 application 层。kernel 只给积木（`SubAgentTool`/`StructuredOutputTool`），不给工作流——内置某种编排会被某个领域的形状污染。

### 依赖纪律（单向）
- agent 包 **只能单向依赖 kernel，kernel 绝不反向依赖任何 agent 包**。Maven 模块边界 + ArchUnit `kernelHasNoDiagnosisDependency` 已强制；新加 coding 包同此规则。
- 领域概念不准漏进 kernel：`Evidence`/`Hypothesis`/`DiagnosisPlan` 留诊断包，`Patch`/`ReviewVerdict` 留 coding 包。`SubAgentTool` 的描述和 javadoc 已保持领域中立；后续不得重新引入 diagnosis/coding 术语。

### kernel 扩展基座状态（让"加 agent"变薄）
1. **`StructuredAgent` + `AgentSpec`（已完成）**：kernel 已收敛"受约束运行 → 结构化 payload"样板；角色用 domain `AgentSpec` 物化 system prompt、能力集、model tier、预算/限制与 terminal spec。kernel 只返回通用 payload，payload→VO 映射留在 agent 包。历史 TDD 草图见 `docs/structured-agent-tdd-draft.md`。
2. **`AgentManifest`（S10 #54）**：agent 自描述（id / description / entryPoint / requiredConfigKeys），让运行时/CLI 发现与派发，取代 `AgentKitApplication.main` 手工 wiring。等 #47–#53 基座稳定后实施，不引入反射扫描或插件子系统。
