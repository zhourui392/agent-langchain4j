# AgentKit on LangChain4j — 详细设计方案

> 目标：用 Java + LangChain4j 实现 CLI Agent 的核心主流程（消息循环、工具调用、上下文管理、权限、子 Agent、流式输出），以 CLI 形态交付可运行的 MVP。
>
> 设计原则：DDD 四层架构、SOLID、TDD（测试先行）、组合优于继承、命名即文档。

---

## 1. 项目目标与边界

> **定位已转变（2026-06-08）**：交付物从 CLI 转为 agent-web 的**进程内只读诊断引擎**，详见 [§16.1](#161-定位转变进程内诊断引擎2026-06-08)。下文原 CLI 目标作为底座能力保留（多数已被引擎复用），但 CLI 不再是主交付。

### 1.1 必须实现（MVP P0）

| 能力 |
|---|
| 多轮 tool-use 主循环（assistant ↔ tool_result 交替直至模型停止） |
| Anthropic 流式响应（SSE，逐 token 输出） |
| 内置工具：`Bash` / `FileRead` / `FileWrite` / `FileEdit` / `Glob` / `Grep` |
| 工具权限拦截（allow / ask / deny） |
| 系统上下文注入（AGENTS.md、cwd、git 状态、日期） |
| 对话记忆（追加/截断） |
| 中断（Ctrl-C / AbortController） |

### 1.2 计划实现（P1）

- 上下文压缩（auto-compact）
- 子 Agent（`AgentTool` 等价物）
- MCP 客户端集成
- 持久化记忆（memdir）
- 斜杠命令（`/help` `/clear` `/compact` `/cost`）

### 1.3 不在范围内（P2+）

- **多模态输入**（图片/截图粘贴）— P2 评估
- Ink/React 终端 UI（用纯 JLine 替代）
- IDE Bridge、Remote Session、Plugin
- OAuth/Keychain（用 `AK_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` 环境变量）

---

## 2. 整体架构（DDD 四层）

```
┌────────────────────────────────────────────────────────────┐
│ Interface 层 (cli)                                          │
│   CommandLineRunner · REPLLoop · SlashCommandParser         │
│   职责：终端 I/O、参数解析、流式输出渲染                       │
└────────────────────────────────────────────────────────────┘
                          ↓
┌────────────────────────────────────────────────────────────┐
│ Application 层 (application)                                │
│   AgentExecutor · ConversationOrchestrator                  │
│   PermissionService · ContextCompactionService              │
│   职责：用例编排、事务边界、跨聚合协作；禁止业务规则           │
└────────────────────────────────────────────────────────────┘
                          ↓
┌────────────────────────────────────────────────────────────┐
│ Domain 层 (domain)                                          │
│   聚合：Conversation · ToolInvocation · CompactionWindow    │
│   接口：Tool · PermissionPolicy · ContextProvider           │
│   职责：核心业务规则；零外部依赖                              │
└────────────────────────────────────────────────────────────┘
                          ↑
┌────────────────────────────────────────────────────────────┐
│ Infrastructure 层 (infrastructure)                          │
│   llm/       LangChain4j StreamingChatModel 封装             │
│   tools/     BashTool / FileEditTool 等具体实现              │
│   memory/    ChatMemoryStore 文件实现                        │
│   mcp/       langchain4j-mcp 适配                            │
│   context/   GitStatusProvider · AgentsMdProvider            │
│   职责：技术实现细节；实现 Domain 层定义的端口                 │
└────────────────────────────────────────────────────────────┘
```

依赖方向：`interface → application → domain ← infrastructure`。Domain 层不依赖任何外层（包括 LangChain4j SDK）。

---

## 3. 主流程时序

```
User 输入
  │
  ▼
[REPLLoop] 读取行 → [SlashCommandParser]
  │                       │
  │                       └→ 内置命令分支（/help /clear ...）
  ▼
[ConversationOrchestrator.submit(userInput)]
  │
  ▼
[AgentExecutor.run(conversation)]
  │
  │   ┌─────────────────────────── loop ───────────────────────────┐
  │   │ 1. ContextCompactionService.maybeCompact(conversation)     │
  │   │ 2. ContextProvider.buildSystemPrompt() → SystemMessage     │
  │   │ 3. LlmClient.streamChat(messages, tools, handler)           │
  │   │      handler.onPartial → renderer.print(token)               │
  │   │      handler.onComplete → AiMessage(text + toolRequests)    │
  │   │ 4. conversation.append(aiMessage)                          │
  │   │ 5. 若无 toolRequests → break                               │
  │   │ 6. for each toolReq:                                       │
  │   │      PermissionService.check(toolReq) → ALLOW / ASK / DENY │
  │   │      Tool.execute(toolReq.args) → ToolResult              │
  │   │ 7. conversation.append(toolResults)                        │
  │   └────────────────────────────────────────────────────────────┘
  │
  ▼
返回最终 AiMessage 给 REPL，等待下一轮输入
```

---

## 4. 核心抽象（Domain 层接口）

### 4.1 `Tool`

```java
public interface Tool {
    String name();
    String description();
    JsonSchema inputSchema();
    boolean isReadOnly();                 // 决定默认权限策略
    ToolResult execute(ToolArguments args, ExecutionContext ctx);
}
```

> 对应 `Tool.ts:Tool` 类型。`isReadOnly()` 用于 `plan` 模式下自动放行只读工具。

### 4.2 `PermissionPolicy`

```java
public interface PermissionPolicy {
    Decision decide(ToolInvocation invocation, PermissionMode mode);
    enum Decision { ALLOW, ASK, DENY }
}
```

> 对应 `hooks/toolPermission/`。`PermissionMode` ∈ `default / plan / bypass / auto`。

### 4.3 `ContextProvider`

```java
public interface ContextProvider {
    String key();                          // "agents_md" / "git_status" / "cwd"
    Optional<String> provide(Conversation conversation);
}
```

> 多个 Provider 由 `SystemPromptComposer` 聚合，对应 `context.ts:getContext`。

### 4.4 `LlmClient`（端口，infrastructure 实现）

```java
public interface LlmClient {
    void streamChat(ChatRequest request, StreamHandler handler);

    interface StreamHandler {
        void onPartialText(String delta);
        void onThinking(String delta);
        void onToolUseStart(ToolUseRequest req);
        void onComplete(AiMessage message, Usage usage);
        void onError(Throwable error);
    }
}
```

> Domain 层只看得到这个端口；LangChain4j 的 `StreamingChatModel` 在 infrastructure 实现里隐藏。

### 4.5 `ChatMemoryStore`（端口）

```java
public interface ChatMemoryStore {
    List<ChatMessage> load(SessionId id);
    void save(SessionId id, List<ChatMessage> messages);
    void delete(SessionId id);
}
```

> 文件实现写入 `~/.agentkit/sessions/<id>.jsonl`，对应 `memdir/`。

---

## 5. 关键聚合

### 5.1 `Conversation`

```java
public final class Conversation {
    private final SessionId id;
    private final List<ChatMessage> messages;     // 按顺序追加
    private final TokenBudget budget;
    private CompactionBoundary lastCompaction;    // 可空

    public void append(ChatMessage msg) { ... }
    public List<ChatMessage> messagesAfterCompaction() { ... }
    public int estimatedTokens() { ... }
}
```

不变式：`messages` 中 `tool_use` 必有配对 `tool_result`；压缩边界前后的消息不可乱序。

### 5.2 `ToolInvocation`

```java
public final class ToolInvocation {
    private final ToolUseId id;
    private final String toolName;
    private final ToolArguments args;
    private final Instant requestedAt;
    private ToolResult result;                    // 可空，执行后填充
    private Decision permissionDecision;
}
```

---

## 6. Application 层关键服务

### 6.1 `AgentExecutor`

> 主轮次循环，项目的"心脏"。

```java
public final class AgentExecutor {
    private final LlmClient llm;
    private final ToolRegistry tools;
    private final PermissionService permissions;
    private final ContextCompactionService compaction;
    private final SystemPromptComposer systemPrompt;
    private final OutputRenderer renderer;

    public CompletableFuture<AiMessage> run(
        Conversation conversation,
        CancellationToken cancel
    ) { ... }
}
```

**关键约束：**
- 单方法 ≤ 50 行：循环体抽取为 `executeTurn(...)`、`dispatchToolCalls(...)`、`renderStream(...)` 三个私有方法。
- 嵌套 ≤ 3 层：tool 并发用 `parallelStream()` 替代嵌套 for。
- 每次循环开头检查 `cancel.isCancelled()`，及早退出。

### 6.2 `PermissionService`

```java
public final class PermissionService {
    private final PermissionPolicy policy;
    private final InteractivePrompter prompter;     // CLI 询问

    public Decision check(ToolInvocation inv) {
        Decision d = policy.decide(inv, currentMode());
        return d == Decision.ASK ? prompter.ask(inv) : d;
    }
}
```

### 6.3 `ContextCompactionService`

```java
public final class ContextCompactionService {
    private final LlmClient llm;
    private final TokenEstimator estimator;
    private static final double COMPACT_THRESHOLD = 0.85; // 85% of context

    public void maybeCompact(Conversation conv) {
        if (estimator.estimate(conv) < threshold()) return;
        String summary = llm.summarize(conv.messagesAfterCompaction());
        conv.installCompactionBoundary(summary);
    }
}
```

> 对应 `services/compact/autoCompact.ts`。MVP 用阈值触发；P2 再加 reactive compact。

---

## 7. Infrastructure 层实现要点

### 7.1 `LangChain4jLlmClient`

```java
public final class LangChain4jLlmClient implements LlmClient {
    private final AnthropicStreamingChatModel model;

    @Override
    public void streamChat(ChatRequest req, StreamHandler handler) {
        var lcRequest = dev.langchain4j.model.chat.request.ChatRequest.builder()
            .messages(toLcMessages(req.messages()))
            .toolSpecifications(toLcTools(req.tools()))
            .build();

        model.chat(lcRequest, new StreamingChatResponseHandler() {
            public void onPartialResponse(String token) { handler.onPartialText(token); }
            public void onCompleteResponse(ChatResponse r) {
                handler.onComplete(toDomainMessage(r.aiMessage()), toUsage(r));
            }
            public void onError(Throwable t) { handler.onError(t); }
        });
    }
}
```

### 7.2 工具实现样例：`BashTool`

```java
public final class BashTool implements Tool {
    public String name() { return "Bash"; }
    public boolean isReadOnly() { return false; }

    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        String command = args.getString("command");
        int timeout = args.getInt("timeout", 120_000);
        return ProcessRunner.run(command, ctx.cwd(), timeout, ctx.cancellation());
    }
}
```

> `ProcessRunner` 是基础设施工具类。所有外部进程调用集中在这里，便于注入测试 Stub。

### 7.3 工具实现样例：`FileEditTool`

实现要点（对照 `tools/FileEditTool/`）：
1. **读后改约束**：会话级 `FileStateCache` 记录已 `Read` 过的文件；未读不可 `Edit`。
2. **唯一性校验**：`oldString` 在文件中只能出现一次，否则报错并提示加上下文。
3. **diff 渲染**：返回结果包含统一 diff，供 UI 渲染。

### 7.4 `FileChatMemoryStore`

- 单会话单文件，JSONL 格式（每行一条消息）。
- 写入用 append 模式 + fsync，避免崩溃丢失。
- 路径：`${user.home}/.agentkit/sessions/<sessionId>.jsonl`。
- **恢复语义**：`/resume` 只重放消息历史，**不重新执行工具调用**。`tool_use` 与 `tool_result` 配对从文件原样加载到 `Conversation`。

---

## 8. 流式渲染与中断

### 8.1 渲染

`OutputRenderer` 在 `onPartialText` 中将 token 写入 `System.out` 并 flush。不缓冲、不行包装，让用户看到逐字输出（无富文本渲染）。

### 8.2 中断

- `CancellationToken` 是组合优于继承的轻量原子布尔。
- REPL 注册 `SIGINT` 处理器：第一次 Ctrl-C 设置 `cancel=true`，让当前 turn 优雅退出；第二次直接 `System.exit(130)`。
- LangChain4j 流式不直接支持取消，方案：`onPartialText` 内部检查 `cancel`，命中时抛 `CancellationException` 由上层捕获并丢弃后续 token。

---

## 9. 子 Agent（P1）

`SubAgentTool` 在执行时实例化一个新的 `AgentExecutor`：

```java
public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
    Conversation sub = new Conversation(SessionId.fresh());
    sub.append(UserMessage.from(args.getString("prompt")));

    ToolRegistry narrowed = tools.filter(args.getList("allowed_tools"));
    AgentExecutor child = new AgentExecutor(llm, narrowed, ...);

    AiMessage result = child.run(sub, ctx.cancellation()).join();
    return ToolResult.text(result.text());
}
```

隔离原则：子 Agent 不共享父 `Conversation`，只继承 `cwd`、`PermissionMode`、`LlmClient`。

---

## 10. 技术选型

| 关注点 | 选择 | 理由 |
|---|---|---|
| 语言/JDK | Java 21 | 虚拟线程 + pattern matching 简化并发与解构 |
| 构建 | Maven 3.9+ | 生态最稳；LangChain4j 官方文档以 Maven 为主 |
| LLM SDK | langchain4j 1.0+ | 原生 `AnthropicStreamingChatModel` + tool support |
| MCP | langchain4j-mcp | 直接复用，避免造轮子 |
| CLI | picocli + JLine | picocli 处理参数；JLine 处理 REPL 行编辑/历史 |
| JSON | jackson-databind | 工具 schema、记忆持久化 |
| 进程执行 | zt-exec | 比 `ProcessBuilder` 更稳，超时和流捕获完善 |
| 文件搜索 | ripgrep（外部进程） | 性能优先，缺失时回退 Java 正则 |
| 测试 | JUnit 5 + AssertJ + Mockito | TDD 标配 |
| 日志 | SLF4J + Logback | 标准 |
| 配置 | 环境变量 + `~/.agentkit/config.json` | 简单 |

显式不引入：Spring（过重）、Guice（不必要）、Lombok（团队偏好可议，默认不用以保持显式）。

---

## 11. 项目结构

```
agentkit/
├── pom.xml
├── DESIGN.md                            ← 本文件
├── README.md
├── agentkit-kernel/                         ← 通用 Agent 底座，无 CLI / 诊断语义
│   └── src/main/java/com/anthropic/agentkit/
│       ├── application/                 ← AgentExecutor、预算、权限、上下文压缩
│       ├── domain/                      ← Conversation、Message、Tool、Permission、Port
│       └── infrastructure/              ← LLM、stream-json、通用 tools、治理包装链
│
├── agentkit-agent-diagnosis/                ← 诊断专用层，宿主依赖的 artifact
│   └── src/main/java/com/anthropic/agentkit/
│       ├── interfaces/engine/           ← DiagnoseEngine、RunRequest、Builder
│       ├── domain/diagnosis/            ← DiagnosisCase、Plan、Evidence、Report
│       ├── application/diagnosis/       ← PlanGuardPolicy 等诊断编排策略
│       └── infrastructure/diagnosis/    ← Planner、Reporter、StateCodec、ToolFactory
│
├── agentkit-cli/                            ← 调试壳，不进入宿主 classpath
│   └── src/main/java/com/anthropic/agentkit/
│       └── interfaces/cli/              ← AgentKitApplication、JLine REPL、渲染
│
└── docs/
    ├── archive/
    │   ├── agent-platform-layering-design.md
    │   ├── diagnose-engine-plan.md
    │   ├── diagnosis-agent-capability-design.md
    │   ├── engine-integration-hardening-plan.md
    │   └── skill-mechanism-design.md
    ├── samples/
    └── skill-authoring.md
```

---

## 12. 开发流程（TDD）

> 测试用例是交付资产，必须严格 Red-Green-Refactor。

### 12.1 起手三个测试（决定 Domain 形状）

1. **`ConversationTest.appendsToolUseAndToolResultInOrder`**
   验证 `Conversation` 拒绝在没有对应 `tool_use` 时追加 `tool_result`。

2. **`AgentExecutorTest.stopsWhenAssistantHasNoToolUse`**
   用 `StubLlmClient` 返回一条纯文本 `AiMessage`，断言循环只跑一轮。

3. **`AgentExecutorTest.executesToolAndFeedsResultBackToModel`**
   `StubLlmClient` 第一次返回带 `tool_use` 的消息，第二次返回纯文本；断言：
   - 工具被调用一次
   - `tool_result` 被追加到 conversation
   - 第二次 LLM 调用看到了 `tool_result`

### 12.2 工具测试

每个工具至少三个测试：成功路径、参数校验失败、外部错误（文件不存在 / 命令非零退出）。

### 12.3 集成测试

`EndToEndIT`：用真实 `ANTHROPIC_API_KEY`（CI 跳过，本地手测），跑一个 "list files in cwd" 请求，断言模型调用了 `GlobTool` 并给出合理文本回复。

---

## 13. 实施路线图

| 阶段 | 范围 | 退出标准 |
|---|---|---|
| **S0 脚手架**（0.5d） | pom、目录、CI、空 main | `mvn test` 通过（0 测试） |
| **S1 Domain 骨架**（1d） | `Conversation` / `ChatMessage` / `Tool` 接口 | 12.1 三个测试全绿 |
| **S2 LLM 端口 + Stub**（0.5d） | `LlmClient` 接口、`StubLlmClient` | `AgentExecutor` 完成主循环且测试通过 |
| **S3 LangChain4j 接入**（1d） | `LangChain4jLlmClient` + 流式 + tool spec 映射 + **prompt cache breakpoint** | 能跑通真实 API 一轮对话，cache hit 可观测 |
| **S4 内置工具**（2d） | Bash / FileRead / FileWrite / FileEdit / Glob / Grep | 每个工具有 3+ 测试，端到端能编辑文件 |
| **S5 权限系统**（1d） | `PermissionService` + 4 种模式 + 交互 prompt | 权限矩阵测试覆盖 |
| **S6 上下文 + REPL**（1d） | `ContextProvider`、CLAUDE.md 注入、JLine REPL | 可交互使用 |
| **S7 中断 + 流式渲染**（0.5d） | Ctrl-C、token 实时输出 | 手测通过 |
| **S8 持久化**（0.5d） | `FileChatMemoryStore`、`/resume` | 重启后恢复会话 |
| **MVP 完成线** | ↑ 以上为 MVP | ≈ 8 人天 |
| S9 上下文压缩 | `ContextCompactionService` | 长会话不爆 context |
| S10 子 Agent | `SubAgentTool` | 嵌套循环可隔离 |
| S11 MCP | `McpToolAdapter` | 外部 MCP server 工具可用 |
| S12 斜杠命令 | `/compact /cost /clear /help` | 命中常用流程 |
| S13 JSON 单次模式 | `--json` 非交互入口 | 脚本化调用，stdout 仅输出最终 JSON |

---

## 14. 风险与取舍

### 14.1 已识别风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| LangChain4j 的 Anthropic provider 对 `thinking` 块支持滞后 | 思考模式可能不可用 | MVP 不依赖 thinking；P2 评估是否绕过 LC4J 直连 Anthropic SDK |
| `StreamingChatResponseHandler` 不能主动取消 | Ctrl-C 体验差 | 在 handler 内自查 cancel 标志后丢弃 token；P2 提交 PR 给 LC4J |
| 工具并发执行需要保持 `tool_result` 顺序 | 模型要求 `tool_use` 和 `tool_result` 一一对应 | 每个 request 独立生成终态 outcome，以有序 future 列表回收并按原批次顺序写入；domain `AssistantTurn` 强制完整配对 |
| Anthropic prompt cache 命中率取决于消息前缀稳定性 | 成本/延迟 | system prompt 拼接顺序固化在 `SystemPromptComposer`，禁止动态字段插在前面 |
| Windows 上 ripgrep / bash 不一定有 | 工具不可用 | Bash 工具检测平台用 `cmd.exe`；Grep 工具优先 ripgrep，回退 Java 实现 |

### 14.2 已决定的取舍

- **provider 抽象保持窄口径** — 只支持 §16.6 记录的 OpenAI / Anthropic 两个 LangChain4j 工厂，不引入通用 SPI、反射扫描或第三方 provider 扩展面。
- **不做 Ink 等价物** — 纯文本输出。UI 不是核心价值。
- **不引入 Spring** — `main()` 手工组装依赖图，对小项目最清晰。
- **配置不走代码注解** — 全部环境变量 + JSON 文件，避免 reflection / 启动魔法。
- **不复刻 feature flag 死代码消除** — Java 没有等价机制；用普通条件分支 + Maven profile。

---

## 15. 组件总览

| 职责 | 本项目组件 |
|---|---|
| CLI 入口 + REPL | `AgentKitApplication` + `ReplLoop` |
| 主循环执行 | `AgentExecutor` + `ConversationOrchestrator` |
| 工具抽象 + 注册表 | `domain/tool/Tool.java` + `ToolRegistry` |
| 系统上下文组装 | `SystemPromptComposer` + `ContextProvider` 实现 |
| 上下文压缩 | `agentkit-kernel/application/context/ContextCompactionService` |
| 权限拦截 | `PermissionService` + `PermissionPolicy` |
| 工具分发 | `AgentExecutor.dispatchToolCalls` |
| LLM 适配 | `LangChain4jLlmClient` |
| 会话持久化 | `FileChatMemoryStore` |
| 子 Agent | `agentkit-kernel/infrastructure/tools/SubAgentTool` |
| Bash / 文件编辑 | `BashTool` + `ProcessRunner` / `FileEditTool` + `FileStateCache` |
| stream-json 输出 | `agentkit-kernel/infrastructure/streamjson/ClaudeStreamJsonListener` / `ClaudeStreamJsonWriter` |
| 诊断进程内门面 | `agentkit-agent-diagnosis/interfaces/engine/DiagnoseEngine` / `RunRequest` |
| CLI 调试入口 | `agentkit-cli/interfaces/cli/AgentKitApplication` |

---

## 16. 已确认决策（2026-05-17）

| 议题 | 决策 | 影响 |
|---|---|---|
| 多模态输入 | **MVP 不做**，延至 P2 评估 | 节省 ≈1 周；`UserMessage` 内容暂只建模 `text` 类型 |
| 会话恢复粒度 | **仅恢复消息历史**，不重放工具 | `FileChatMemoryStore` 原样持久化 `tool_use` / `tool_result` 对 |
| JSON 单次模式 | **纳入 P1**（S13） | `--json` flag + 非交互入口，stdout 只输出最终 JSON 结果 |
| Prompt cache | **S3 起启用** | `SystemPromptComposer` 在系统提示末尾标记 cache breakpoint；工具定义稳定后再标一次 |

### 16.1 定位转变：进程内诊断引擎（2026-06-08）

本项目从 CLI 形态转为 **agent-web 的进程内只读诊断引擎**。完整方案见 [`docs/archive/diagnose-engine-plan.md`](docs/archive/diagnose-engine-plan.md)，wire 契约见 [`docs/samples/README.md`](docs/samples/README.md)。

| 议题 | 决策 | 影响 |
|---|---|---|
| 交付形态 | **进程内 jar**，非 CLI | 新增 `interfaces/engine`（`DiagnoseEngine` 门面 + `ClaudeStreamJsonListener/Writer`）；`interfaces/cli` 降级为调试入口；不引入 web 框架 |
| 工具边界 | **纯只读诊断** | `ReadOnlyPermissionPolicy`（只读 ALLOW / 写 DENY，无 ASK）；新增只读工具 Http/Es/Mysql/Redis/Dubbo（各带读限守卫）；`FileWrite`/`FileEdit` 不注册 |
| 会话状态 | **引擎无状态** | `ConversationRebuilder` 按传入 history 重建；旁路 `FileChatMemoryStore`；`stop`/超时经 `RunningSessions` + `CancellationToken` |
| 事件契约 | **Claude `stream-json` 直通** | 增量须包 `{"type":"stream_event","event":{…}}`；`AgentExecutor` 不改，仅加 additive `onUsage` 钩子 |

已实现（分支 `feat/diagnose-engine`）：E0–E3 引擎 MVP、E5 只读工具（含 `LogQueryTool`）、E6 截断 + auto-compact、E7 `SubAgentTool`、E8 usage 透出（含 Anthropic `cache_read_input_tokens`）。**未完**：E4（agent-web 接入 `AgentType.NATIVE` + 手测，跨仓库）。

### 16.2 双层产品形态与三模块拆分（2026-06-11）

本项目从单 Maven jar 拆为 **通用 Agent 底座 + 诊断专用 Agent + CLI 调试壳**。完整方案见 [`docs/archive/agent-platform-layering-design.md`](docs/archive/agent-platform-layering-design.md)。

| 议题 | 决策 | 影响 |
|---|---|---|
| 物理模块 | parent reactor 下设 `agentkit-kernel`、`agentkit-agent-diagnosis`、`agentkit-cli` | 宿主只依赖诊断 artifact，kernel 传递带入，CLI/JLine 不进入宿主 classpath |
| kernel 语义 | kernel 不感知专用方向，不出现 diagnosis 语义 | 第二个专用 Agent 可复用主循环、预算、stream-json、结构化输出、治理包装链 |
| 通用件归位 | `ContextCompactionService`、`SubAgentTool`、`ClaudeStreamJsonListener/Writer` 移入 kernel | `interfaces.engine` 只保留诊断门面与事件契约对象 |
| 诊断能力归位 | `AgentBudget`、`StructuredOutputTool`、`GovernedTool` 等机制下沉 kernel，诊断层只注册 schema、规则和后端 | 避免诊断设计复制通用能力 |

### 16.3 CLI 降级与正式集成形态（2026-06-11）

| 议题 | 决策 | 影响 |
|---|---|---|
| 正式集成 | **进程内 Java API** 是唯一正式形态 | `DiagnoseEngineBuilder` 是宿主组装根；不做 CLI 子进程、Server、RPC 或插件扫描 |
| CLI 定位 | `agentkit-cli` 只作为 kernel 调试壳 | CLI 可单独运行 REPL，但不随诊断专用层发布 |
| 对外稳定面 | 仅 `DiagnoseEngine`、`RunRequest`、stream-json 事件契约纳入兼容承诺 | 其余类按 internal 处理，优先保持边界清晰 |

### 16.4 引入 Skill 子系统（2026-06-13）

推翻 §1.3 中「Skill 子系统 out of scope」与 capability-design §5.1 中「MVP 不引入可执行 Skill 机制」两项决策。动机：PromptPack 全量注入随场景数线性膨胀，模型无按需取用能力。完整方案见 [`docs/archive/skill-mechanism-design.md`](docs/archive/skill-mechanism-design.md)。

| 议题 | 决策 | 影响 |
|---|---|---|
| Skill 形态 | 目录 + `SKILL.md`（YAML frontmatter），对齐通用 Agent Skills 约定 | 渐进暴露三级：目录注入 name+description，调用时返回正文，附属文件按需 Read |
| 归属模块 | kernel 通用件（`domain.skill` + `infrastructure.skill`），诊断层和 CLI 仅装配 | 第二个专用 Agent 可直接复用；kernel 不出现 diagnosis 语义 |
| 能力边界 | **知识型 Skill**，不含可执行脚本 | 与只读诊断引擎姿态一致；Bash 不因 Skill 自动开放 |
| PromptPack 去留 | 保留，二者分工：常驻必读走 PromptPack，按需取用走 Skill | 存量 SOP 渐进迁移，不做一次性切换 |

### 16.5 引擎宿主集成强化（2026-06-13）

完整方案见 [`docs/archive/engine-integration-hardening-plan.md`](docs/archive/engine-integration-hardening-plan.md)。

| 议题 | 决策 | 影响 |
|---|---|---|
| 终态契约 | `DiagnoseEngine.run(..., Consumer<RunSummary>)` 为主接口，旧 `runStream` 默认委托 | 宿主可直接落库 `ExitReason`、state snapshot、usage 与错误摘要 |
| stream-json history | Writer 在 assistant 轮末补整合 `type=assistant` 行，`StreamJsonHistoryParser` 与 Writer 同仓演进 | 持久化 NDJSON 可反解为 `List<TurnMessage>`，工具配对不变式由解析器过滤孤儿侧保护 |
| 后端装配 | `DiagnosisBackendConfig` + `DiagnosisToolBackendsFactory` 从显式连接配置创建后端 | 宿主无需手写 6 类 client，凭证仍只由宿主传入 |
| 退出语义 | `ExitReason` 拆分 SUCCESS / STOPPED / TIMEOUT / ERROR / REJECTED | 旧 exit code 兼容，前端与历史可区分 stop、timeout 和拒绝 |
| 服务端生命周期 | `DiagnoseEngine` 实现 `AutoCloseable`，重复 sessionId 和并发上限快速 REJECTED | Spring `destroyMethod=close` 可 graceful drain，旧 run 不会被新 token 覆盖 |
| usage | `LangChain4jLlmClient` 识别 `AnthropicTokenUsage.cacheReadInputTokens()` | prompt cache 成本统计不再固定为 0；非 Anthropic usage 仍保持 0 不估算 |

### 16.6 接入 OpenAI 协议（2026-06-13）

推翻 §14.2 中「只支持 Anthropic」的取舍，新增 OpenAI-compatible provider，并把默认 provider 切到 `OPENAI`。完整方案见 [`docs/openai-provider-plan.md`](docs/openai-provider-plan.md)。

| 议题 | 决策 | 影响 |
|---|---|---|
| provider 选择 | `AK_PROVIDER` / config `provider` 支持 `OPENAI`、`ANTHROPIC`，默认 `OPENAI` | 本地默认走 OpenAI-compatible endpoint；旧 Anthropic 用户需显式 `AK_PROVIDER=anthropic` |
| 凭据别名 | API key 依次读取 `AK_API_KEY`、`OPENAI_API_KEY`、`ANTHROPIC_API_KEY`；baseUrl 依次读取 `AK_BASE_URL`、`OPENAI_BASE_URL`、`ANTHROPIC_BASE_URL` | 兼容旧环境变量，同时提供 provider-neutral 配置 |
| 默认模型 | OpenAI 默认 `gpt-5.5`；Anthropic 默认 `claude-sonnet-4-6` | `AK_MODEL` 仍可覆盖 |
| 实现边界 | `LangChain4jLlmClient` 继续依赖通用 `StreamingChatModel`；provider 差异只在工厂层 | 主循环、消息映射、工具 schema、权限链不改 |
| prompt cache | Anthropic 工厂保留 `CacheBreakpointStrategy`；OpenAI 工厂不设置 prompt-cache marker | OpenAI 路径 cache token 统计保持 0，不估算 |

---

### 16.7 coding agent 多角色流水线落地（2026-06-20）

正式采纳 AGENTS.md「多角色协作演进原则」，`agentkit-agent-coding` 落地第一个 coding agent 包：单趟（single-pass）`plan → patch → review` 流水线，复用 kernel `StructuredAgent` / `StructuredOutputTool`。

| 议题 | 决策 | 影响 |
|---|---|---|
| 角色物化 | 每个角色 = `StructuredAgent` + 角色配置（systemPrompt、终结工具 spec、domainTools），不走子类 | `StructuredCodingPlanner` / `StructuredCodingPatcher` / `StructuredCodingReviewer` 三个 final 类，各自只持角色配置 + payload→VO 映射 |
| 能力硬边界 | 写能力钉在构造签名上：Coder 注入 `List<Tool>`（读写），Reviewer 构造不接收任何工具 | 边界由类型强制，不靠 prompt 自觉；Reviewer 拿不到写工具 |
| 交接载体 | 终结工具 schema 即交接 VO：`update_plan→CodingPlan`、`submit_patch→Patch`、`submit_review→ReviewVerdict` | 零文本解析；payload→VO 映射留 agent 包 infra，kernel 只回通用 `Map` |
| 编排归属 | `CodingPipeline` 在 agent 包 application 层，纯顺序委托驱动 `CodingTask` 聚合根 | 状态迁移守卫 + verdict→status 全在聚合根，pipeline 无业务分支 |
| 重试/转人工 | 单趟作用域：任何非 ACCEPT 的 verdict 即终止（REJECTED）；重试循环与 NEEDS_HUMAN 非终结留聚合根的未来增量 | pipeline 不写 `if (retryCount < n)` |
| Reviewer 兜底 | `decision` 非 schema 必填，缺省由映射兜底 `NEEDS_HUMAN` | 判定不清晰时升级人工而非崩溃 |

**暂不做**：`AgentManifest` / CLI 派发（等第二个可派发 agent 入口真要插入）、容器沙箱（L2 拐点）。

### 16.8 AgentRunContext 单一运行作用域（2026-07-29）

推翻 §16.7 中“暂不做 `ExecutionContext` cwd 透传”的延后决定。diagnosis 与 coding 已经形成两个独立 agent 包；若继续由角色内部使用 `Path.of(".")`，同一 executor 并发服务不同 worktree 时会把工具送进错误目录。

| 议题 | 决策 | 影响 |
|---|---|---|
| 静态与动态状态 | `AgentExecutor` 只持 `LlmClient`、`ToolRegistry`、`PermissionService` 静态依赖；`run` 必须显式接收 `AgentRunContext` | executor 不捕获 cwd、cancellation 或 budget，可并发复用 |
| 运行身份 | `AgentRunContext` 是 `RunId`、`SessionId`、`WorkspaceId`、规范化 workspace root、cancellation、budget 的单一事实来源 | conversation session 不匹配时 fail-fast；kernel executor 不读取 `user.dir` |
| 工具视图 | `ExecutionContext` 是从 `AgentRunContext` 派生的受限工具执行视图，不是第二份运行配置 | 所有工具收到同一 RunId/workspace/cancellation/budget；生产入口不得自行制造默认 context |
| 可变状态 | dispatcher 与 budget guard 收敛进每次 run 创建的私有 `AgentRunState` | 并发 run 不共享 dispatcher 或预算消费 |
| 授权隔离 | permission `ALLOW_ALWAYS` cache 按 RunId；文件先读后写 cache 按 `(RunId, WorkspaceId, normalizedPath)` | run 结束清理权限；同 workspace 的另一 run 也不能复用读授权 |
| 子 Agent 与事件 | `SubAgentTool` 建新 child session，但继承父 RunId/workspace/cancellation/budget；listener 通过 `onRunStart` 接收完整 context | 子 Agent、工具、权限、日志和事件可用同一 RunId 关联 |
| agent 包透传 | diagnosis orchestrator 从请求 cwd 构造一次 context；coding pipeline 接收一次 context，按 Planner → Patcher → Reviewer 原样传递 | 领域角色不再读取进程 cwd，且编排规则仍留在各 agent 包 application 层 |

`AgentRunContext` 仍只承载运行能力边界，不承载任意集合或 agent 领域状态。deadline、child limits 等后续运行限制可以演进为明确的 limits VO；不得把全局单例或 `ThreadLocal` 变成事实来源。MDC 只保留日志投影职责。

---

### 16.9 AssistantTurn 工具批次完整结算（2026-07-29）

一次 assistant 响应中的 tool-use 列表是强一致批次，不是若干彼此无关的异步任务。模型进入下一轮前，该批次必须按原顺序、每项恰好一次地写入终态 result；任何预期失败也属于终态，不能以异常跳过消息配对。

| 议题 | 决策 | 影响 |
|---|---|---|
| 批次聚合 | domain `AssistantTurn` 持有原始 `ToolUseId` 顺序和 `RECEIVED → SETTLING → SETTLED` 状态 | 重复 ID、乱序 result、pending 时追加 user/assistant 都在 `Conversation.append` 前被拒绝，失败写入不改变 conversation |
| 结果模型 | `ToolResultStatus` 是终态事实源：SUCCESS / ERROR / DENIED / CANCELLED / TIMEOUT / UNKNOWN_TOOL / INVALID_ARGUMENTS / BUDGET_EXHAUSTED | `ToolResult.success()` 仅是派生便利方法；dispatcher 与 `ToolInvocation` 不再维护另一套 complete/fail 布尔分支 |
| 分发合同 | dispatcher 输入有序 request，输出等长、有序、全部 terminal 的 `ToolResultMessage` | unknown tool、参数解析、permission、listener、工具异常和取消都独立结算；单个 worker 不能击穿整批 |
| 预算拒绝 | assistant tool-use 已写入后发生预算拒绝时，整批写入 `BUDGET_EXHAUSTED`，随后才继续或结束 run | maxToolCalls/input token 超限不再制造孤儿 tool-use |
| Provider 映射 | LangChain4j 升级到 1.18；粗粒度错误写 `isError`，详细 status 与 metadata 写 message attributes | Anthropic/OpenAI adapter 保留通用错误语义，不需要解析错误文本 |
| 会话兼容 | JSONL 新记录持久化 status/metadata；旧 tool-result 缺 status 时明确默认为 SUCCESS | 已有 session 可继续读取，新会话可无损恢复工具终态 |

listener 是观察者：其异常只记录日志，不改变工具协议结果。若未来引入可阻断 interceptor，必须通过明确的 `ToolResultStatus` 结算，而不能通过抛异常绕开 batch。

---

### 16.10 AgentRunResult 与原生终结工具（2026-07-29）

一次 run 的结束是 kernel 的正式领域结果；结构化交接工具不是“执行后再让模型说 done”的普通工具。运行结果不再从 listener、异常文本或日志拼装，listener 只保留事件投影职责。

| 议题 | 决策 | 影响 |
|---|---|---|
| 统一终态 | `AgentExecutor.run` 返回 `AgentRunResult`，包含 `RunId`、`StopReason`、最终 assistant message、可选结构化 payload、provider usage 和预算消费 | model completed、terminal、预算耗尽和协议错误可由调用方直接分支；不再维护返回 `AiMessage` 的并行合同 |
| 终结能力 | `Tool.kind()` 默认 `STANDARD`；`StructuredOutputTool` 明确为 `TERMINAL`，executor 不识别具体工具名 | 新 agent 包可注册自己的终结 schema，kernel 不引入 diagnosis/coding 领域名 |
| 批次排他 | terminal 必须独占 assistant tool batch；混入普通工具或一次调用多个 terminal 时整批不执行并以 ERROR 配对，run 以 `TOOL_PROTOCOL_ERROR` 结束 | terminal 成功后不会遗留同批副作用；仍满足 §16.9 的完整有序 settle |
| 成功条件 | 只有 schema 校验和 consumer 接受都成功的 terminal result 才以 `TERMINAL_TOOL` 停止；result 先写入 conversation，再返回 | 校验失败或 consumer 异常生成 ERROR result，模型可在下一轮修正；成功场景 LLM 只调用一次 |
| Schema 子集 | kernel 严格支持 object/array/string/number/integer/boolean/null、properties/required/additionalProperties/items/enum，以及描述性 `$schema`/title/description/default；未知关键字 fail-fast | 当前 planner/patcher/reviewer/reporter schema 得到递归类型、枚举、嵌套字段和额外字段验证，不宣称实现完整 JSON Schema 标准 |
| 角色适配 | `StructuredAgent` 直接读取 `AgentRunResult.structuredOutput`；diagnosis 通过 `RunSummaryAdapter` 显式投影 kernel stop reason 与 usage | 结构化角色不再依赖可覆盖 sink 或第二轮响应；diagnosis 的公开 `RunSummary` 保持兼容但只有一个 kernel 终态事实源 |

取消、deadline、provider error 的原生结果化属于 §S9 #44；本节只定义枚举位置，不提前吞掉仍需终止底层 I/O 的异常。

---

### 16.11 Workspace 文件边界、参数级权限与 scoped secret（2026-07-29）

L0 单用户/worktree 运行时必须同时具备两种性质不同的控制：permission 决定“这次动作是否获准”，workspace boundary 决定“即使获准，kernel 文件工具最多能碰哪里”。两者不能互相替代；`Tool.isReadOnly()` 也不构成访问宿主任意路径的授权。

| 议题 | 决策 | 影响 |
|---|---|---|
| 文件边界 | `Read`、`Write`、`Edit`、`Glob`、`Grep` 必经统一 `WorkspaceBoundary` | `..`、workspace 外绝对路径和 symlink real target 越界均在执行层拒绝；权限 ALLOW 不能绕过 |
| 路径语义 | 先做 lexical containment，再由 `NioWorkspacePathResolver` 按当前平台求 real path/最近已存在祖先，最后由 `WorkspacePathPolicy` 再做 containment | workspace 内绝对路径继续兼容；待创建路径从已验证 canonical ancestor 生成，避免沿已存在的越界 symlink 写出 |
| 跨平台验证 | Linux/macOS symlink 测试使用真实符号链接；Windows drive 与 UNC 测试只在 Windows runner 使用真实 Windows `Path` provider | 不在 Linux 上把 `C:\\...` 字符串伪装成 Windows 路径测试；不同 drive/share 的判断跟随平台 provider |
| 授权规则 | `ALLOW_ALWAYS` grant 精确绑定 `RunId + WorkspaceId + toolName + 完整参数`；每次先计算 policy，仅在 ASK 时查询 cache | 一个 Bash command 不会授权另一个 command；跨 run/workspace 不共享；新 DENY 永远可覆盖旧 grant |
| 安全默认值 | `ConfigLoader` 与 `AppConfig` 便利构造默认 `PermissionMode.DEFAULT` | 正常启动不会静默 BYPASS；显式 BYPASS 只允许由清楚声明能力边界的组合根选择 |
| Secret port | domain `SecretProvider` 以 `SecretScope(RunId, WorkspaceId)` 查询；`AgentRunContext` 显式持有并向工具/子 Agent 透传 | 工具不直接读全局环境；当前环境变量 adapter 留在 infrastructure，未来可按同一 scope 换密钥库 |
| 安全审计 | permission 日志记录 run/workspace/tool/decision；secret adapter 记录 run/workspace/name/found；两者不输出参数值或 secret 值 | 审计可以按 run 关联，同时避免凭证进入日志；工具参数 debug 只列键名 |

`WorkspaceBoundary` 只约束 kernel 自带、通过该边界解析路径的文件工具。`BashTool` 虽然显式设置 cwd，并受命令级 permission、timeout、取消与日志治理，但 shell 仍能访问宿主进程可见的绝对路径、网络和进程资源；它不是 sandbox。L0 只在单用户可信宿主假设下使用这组措施。一旦进入多用户 L2，必须使用容器/VM + 鉴权 + secret 隔离，不能靠 Java 路径检查或 prompt 补救。

---

## 17. 下一步

1. agent-web 切换依赖 `agentkit-agent-diagnosis`，通过 `DiagnoseEngineBuilder` 组装。
2. 在宿主侧验证 `AgentType.NATIVE`、stop、历史回放、状态快照往返。
3. 根据真实调用链补齐生产 allowlist、审计 sink 与日志 API 配置。
