# Claude Code on LangChain4j — 详细设计方案

> 目标：用 Java + LangChain4j 复刻 claude-code 的核心主流程（消息循环、工具调用、上下文管理、权限、子 Agent、流式输出），以 CLI 形态交付可运行的 MVP。
>
> 参考实现：`D:\ai_worspace\claude-code\src\`（claude-code 泄露源码，TypeScript）。
>
> 设计原则：DDD 四层架构、SOLID、TDD（测试先行）、组合优于继承、命名即文档。

---

## 1. 项目目标与边界

> **定位已转变（2026-06-08）**：交付物从 CLI 转为 agent-web 的**进程内只读诊断引擎**，详见 [§16.1](#161-定位转变进程内诊断引擎2026-06-08)。下文原 CLI 目标作为底座能力保留（多数已被引擎复用），但 CLI 不再是主交付。

### 1.1 必须实现（MVP P0）

| 能力 | 来源文件 |
|---|---|
| 多轮 tool-use 主循环（assistant ↔ tool_result 交替直至模型停止） | `QueryEngine.ts` / `query.ts` |
| Anthropic 流式响应（SSE，逐 token 输出） | `services/api/claude.ts` |
| 内置工具：`Bash` / `FileRead` / `FileWrite` / `FileEdit` / `Glob` / `Grep` | `tools/` |
| 工具权限拦截（allow / ask / deny） | `hooks/toolPermission/` |
| 系统上下文注入（CLAUDE.md、cwd、git 状态、日期） | `context.ts` |
| 对话记忆（追加/截断） | `query.ts` |
| 中断（Ctrl-C / AbortController） | `utils/abortController.ts` |

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
- OAuth/Keychain（用 `CCLC_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` 环境变量）

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
│   context/   GitStatusProvider · ClaudeMdLoader              │
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

> 与 claude-code 的对应：步骤 1 = `query.ts:autoCompactIfNeeded`；步骤 3 = `QueryEngine.streamLoop`；步骤 6 = `services/tools/toolOrchestration.ts:runTools`。

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
    String key();                          // "claude_md" / "git_status" / "cwd"
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

> 文件实现写入 `~/.claude-code-j/sessions/<id>.jsonl`，对应 `memdir/`。

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

> claude-code 中 `QueryEngine.streamLoop` 的等价物，是项目的"心脏"。

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
- 路径：`${user.home}/.claude-code-j/sessions/<sessionId>.jsonl`。
- **恢复语义**：`/resume` 只重放消息历史，**不重新执行工具调用**。`tool_use` 与 `tool_result` 配对从文件原样加载到 `Conversation`，与 claude-code 行为一致。

---

## 8. 流式渲染与中断

### 8.1 渲染

`OutputRenderer` 在 `onPartialText` 中将 token 写入 `System.out` 并 flush。不缓冲、不行包装，让用户看到逐字输出（与 claude-code 的 Ink 渲染等效，但无富文本）。

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
| 文件搜索 | ripgrep（外部进程） | 与 claude-code 行为一致 |
| 测试 | JUnit 5 + AssertJ + Mockito | TDD 标配 |
| 日志 | SLF4J + Logback | 标准 |
| 配置 | 环境变量 + `~/.claude-code-j/config.json` | 简单 |

显式不引入：Spring（过重）、Guice（不必要）、Lombok（团队偏好可议，默认不用以保持显式）。

---

## 11. 项目结构

```
claude-code-langchain4j/
├── pom.xml
├── DESIGN.md                            ← 本文件
├── README.md
├── cclc-kernel/                         ← 通用 Agent 底座，无 CLI / 诊断语义
│   └── src/main/java/com/anthropic/cclc/
│       ├── application/                 ← AgentExecutor、预算、权限、上下文压缩
│       ├── domain/                      ← Conversation、Message、Tool、Permission、Port
│       └── infrastructure/              ← LLM、stream-json、通用 tools、治理包装链
│
├── cclc-agent-diagnosis/                ← 诊断专用层，宿主依赖的 artifact
│   └── src/main/java/com/anthropic/cclc/
│       ├── interfaces/engine/           ← DiagnoseEngine、RunRequest、Builder
│       ├── domain/diagnosis/            ← DiagnosisCase、Plan、Evidence、Report
│       ├── application/diagnosis/       ← PlanGuardPolicy 等诊断编排策略
│       └── infrastructure/diagnosis/    ← Planner、Reporter、StateCodec、ToolFactory
│
├── cclc-cli/                            ← 调试壳，不进入宿主 classpath
│   └── src/main/java/com/anthropic/cclc/
│       └── interfaces/cli/              ← CclcApplication、JLine REPL、渲染
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
| 工具并发执行需要保持 `tool_result` 顺序 | 模型要求 `tool_use` 和 `tool_result` 一一对应 | 用 `ConcurrentHashMap<ToolUseId, ToolResult>` 聚合，按 `tool_use` 顺序回填 |
| Anthropic prompt cache 命中率取决于消息前缀稳定性 | 成本/延迟 | system prompt 拼接顺序固化在 `SystemPromptComposer`，禁止动态字段插在前面 |
| Windows 上 ripgrep / bash 不一定有 | 工具不可用 | Bash 工具检测平台用 `cmd.exe`；Grep 工具优先 ripgrep，回退 Java 实现 |

### 14.2 已决定的取舍

- **provider 抽象保持窄口径** — 只支持 §16.6 记录的 OpenAI / Anthropic 两个 LangChain4j 工厂，不引入通用 SPI、反射扫描或第三方 provider 扩展面。
- **不做 Ink 等价物** — 纯文本输出。UI 不是核心价值。
- **不引入 Spring** — `main()` 手工组装依赖图，对小项目最清晰。
- **配置不走代码注解** — 全部环境变量 + JSON 文件，避免 reflection / 启动魔法。
- **不复刻 feature flag 死代码消除** — Java 没有等价机制；用普通条件分支 + Maven profile。

---

## 15. 与 claude-code 源码的对照速查

| claude-code 文件 | 本项目对应 |
|---|---|
| `src/main.tsx` | `CclcApplication` + `ReplLoop` |
| `src/QueryEngine.ts` | `AgentExecutor` + `ConversationOrchestrator` |
| `src/query.ts:queryLoop` | `AgentExecutor.run` 内部循环 |
| `src/Tool.ts` | `domain/tool/Tool.java` |
| `src/tools.ts`（注册表） | `ToolRegistry` |
| `src/context.ts` | `SystemPromptComposer` + `ContextProvider` 实现 |
| `src/services/compact/autoCompact.ts` | `cclc-kernel/application/context/ContextCompactionService` |
| `src/hooks/toolPermission/` | `PermissionService` + `PermissionPolicy` |
| `src/services/tools/toolOrchestration.ts` | `AgentExecutor.dispatchToolCalls` |
| `src/services/api/claude.ts` | `LangChain4jLlmClient` |
| `src/memdir/` | `FileChatMemoryStore` |
| `src/tools/AgentTool/` | `cclc-kernel/infrastructure/tools/SubAgentTool` |
| `src/tools/BashTool/` | `BashTool` + `ProcessRunner` |
| `src/tools/FileEditTool/` | `FileEditTool` + `FileStateCache` |
| Claude stream-json 输出 | `cclc-kernel/infrastructure/streamjson/ClaudeStreamJsonListener` / `ClaudeStreamJsonWriter` |
| 诊断进程内门面 | `cclc-agent-diagnosis/interfaces/engine/DiagnoseEngine` / `RunRequest` |
| CLI 调试入口 | `cclc-cli/interfaces/cli/CclcApplication` |

---

## 16. 已确认决策（2026-05-17）

| 议题 | 决策 | 影响 |
|---|---|---|
| 多模态输入 | **MVP 不做**，延至 P2 评估 | 节省 ≈1 周；`UserMessage` 内容暂只建模 `text` 类型 |
| 会话恢复粒度 | **仅恢复消息历史**，不重放工具 | `FileChatMemoryStore` 原样持久化 `tool_use` / `tool_result` 对 |
| JSON 单次模式 | **纳入 P1**（S13） | `--json` flag + 非交互入口，stdout 只输出最终 JSON 结果 |
| Prompt cache | **S3 起启用** | `SystemPromptComposer` 在系统提示末尾标记 cache breakpoint；工具定义稳定后再标一次 |

### 16.1 定位转变：进程内诊断引擎（2026-06-08）

本项目从「claude-code CLI 复刻」转为 **agent-web 的进程内只读诊断引擎**。完整方案见 [`docs/archive/diagnose-engine-plan.md`](docs/archive/diagnose-engine-plan.md)，wire 契约见 [`docs/samples/README.md`](docs/samples/README.md)。

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
| 物理模块 | parent reactor 下设 `cclc-kernel`、`cclc-agent-diagnosis`、`cclc-cli` | 宿主只依赖诊断 artifact，kernel 传递带入，CLI/JLine 不进入宿主 classpath |
| kernel 语义 | kernel 不感知专用方向，不出现 diagnosis 语义 | 第二个专用 Agent 可复用主循环、预算、stream-json、结构化输出、治理包装链 |
| 通用件归位 | `ContextCompactionService`、`SubAgentTool`、`ClaudeStreamJsonListener/Writer` 移入 kernel | `interfaces.engine` 只保留诊断门面与事件契约对象 |
| 诊断能力归位 | `AgentBudget`、`StructuredOutputTool`、`GovernedTool` 等机制下沉 kernel，诊断层只注册 schema、规则和后端 | 避免诊断设计复制通用能力 |

### 16.3 CLI 降级与正式集成形态（2026-06-11）

| 议题 | 决策 | 影响 |
|---|---|---|
| 正式集成 | **进程内 Java API** 是唯一正式形态 | `DiagnoseEngineBuilder` 是宿主组装根；不做 CLI 子进程、Server、RPC 或插件扫描 |
| CLI 定位 | `cclc-cli` 只作为 kernel 调试壳 | CLI 可单独运行 REPL，但不随诊断专用层发布 |
| 对外稳定面 | 仅 `DiagnoseEngine`、`RunRequest`、stream-json 事件契约纳入兼容承诺 | 其余类按 internal 处理，优先保持边界清晰 |

### 16.4 引入 Skill 子系统（2026-06-13）

推翻 §1.3 中「Skill 子系统 out of scope」与 capability-design §5.1 中「MVP 不引入可执行 Skill 机制」两项决策。动机：PromptPack 全量注入随场景数线性膨胀，模型无按需取用能力。完整方案见 [`docs/archive/skill-mechanism-design.md`](docs/archive/skill-mechanism-design.md)。

| 议题 | 决策 | 影响 |
|---|---|---|
| Skill 形态 | 目录 + `SKILL.md`（YAML frontmatter），对齐 claude-code Agent Skills | 渐进暴露三级：目录注入 name+description，调用时返回正文，附属文件按需 Read |
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
| provider 选择 | `CCLC_PROVIDER` / config `provider` 支持 `OPENAI`、`ANTHROPIC`，默认 `OPENAI` | 本地默认走 OpenAI-compatible endpoint；旧 Anthropic 用户需显式 `CCLC_PROVIDER=anthropic` |
| 凭据别名 | API key 依次读取 `CCLC_API_KEY`、`OPENAI_API_KEY`、`ANTHROPIC_API_KEY`；baseUrl 依次读取 `CCLC_BASE_URL`、`OPENAI_BASE_URL`、`ANTHROPIC_BASE_URL` | 兼容旧环境变量，同时提供 provider-neutral 配置 |
| 默认模型 | OpenAI 默认 `gpt-5.5`；Anthropic 默认 `claude-sonnet-4-6` | `CCLC_MODEL` 仍可覆盖 |
| 实现边界 | `LangChain4jLlmClient` 继续依赖通用 `StreamingChatModel`；provider 差异只在工厂层 | 主循环、消息映射、工具 schema、权限链不改 |
| prompt cache | Anthropic 工厂保留 `CacheBreakpointStrategy`；OpenAI 工厂不设置 prompt-cache marker | OpenAI 路径 cache token 统计保持 0，不估算 |

---

## 17. 下一步

1. agent-web 切换依赖 `cclc-agent-diagnosis`，通过 `DiagnoseEngineBuilder` 组装。
2. 在宿主侧验证 `AgentType.NATIVE`、stop、历史回放、状态快照往返。
3. 根据真实调用链补齐生产 allowlist、审计 sink 与日志 API 配置。
