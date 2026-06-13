# Claude Code on LangChain4j

[![CI](https://github.com/zhourui392/agent-langchain4j/actions/workflows/ci.yml/badge.svg)](https://github.com/zhourui392/agent-langchain4j/actions/workflows/ci.yml)

Java 21 + LangChain4j 1.8 复刻 [claude-code](https://github.com/anthropics/claude-code) 的 CLI 主循环：消息轮转、工具调用、权限、流式输出、上下文注入、会话持久化。

参考实现是 TypeScript 版的 claude-code；本项目用 DDD 四层 + 端口/适配器把 Anthropic 串成 Java 形态，ArchUnit 守住依赖方向。

- 设计：[DESIGN.md](DESIGN.md)
- 分层设计：[docs/agent-platform-layering-design.md](docs/agent-platform-layering-design.md)
- 任务（39 项 / S0–S8 + MVP-Gate）：[TASKLIST.md](TASKLIST.md)
- 协作约定：[CLAUDE.md](CLAUDE.md)

## 已具备的能力

| 模块 | 说明 |
|---|---|
| Agent 主循环 | `AgentExecutor`：流式 LLM → tool_use → 并发执行（虚拟线程）→ tool_result 按原顺序回灌 → 直到模型无 tool_use 终止 |
| 工具 | `Bash` / `Read` / `Write` / `Edit` / `Glob` / `Grep`（ripgrep 自动探测，回退到 Java 正则）|
| 权限 | 4 模式策略（DEFAULT / PLAN / BYPASS / AUTO）+ 交互式 prompt + 会话级 ALLOW_ALWAYS 缓存。**默认 BYPASS（全部自动放行）**，通过 `CCLC_PERMISSION_MODE` 切回 ASK |
| 上下文 | CLAUDE.md（含父级合并）/ cwd / git status / 日期 |
| Prompt cache | 系统指令、CLAUDE.md、工具描述构成稳定前缀；动态段后插入 ephemeral 断点 |
| Skill 机制 | `skills-root/<name>/SKILL.md` 按需展开；目录注入稳定前缀，正文通过只读 `Skill` 工具返回 |
| 流式输出 | 逐 token 渲染，SIGINT 二段式（取消 turn → 退出进程）|
| 持久化 | JSONL 写入 `~/.claude-code-j/sessions/<id>.jsonl`；`/resume <id>` 加载历史不重跑工具 |
| 工具 schema 上线 | `ToolSpec → LC4J JsonObjectSchema → Anthropic input_schema` 全链路打通 |

## 不在范围内

Spring / Guice / Lombok、多 LLM provider 抽象、Ink 风格 TUI、多模态输入、IDE / Plugin 子系统、可执行脚本型 Skill。MCP 已规划（P1）但未实现。

## 环境

- JDK 21（项目用 `--release=21`，Maven Toolchains 可选）
- Maven 3.9+
- 可选 `rg`（ripgrep）—— 缺失时自动回退

## 配置

两种方式，env 覆盖文件：

1. 环境变量
   - `ANTHROPIC_API_KEY`（必需）
   - `CCLC_MODEL`（默认 `claude-sonnet-4-6`）
   - `CCLC_MAX_TOKENS`
   - `CCLC_PERMISSION_MODE`（DEFAULT / PLAN / BYPASS / AUTO，默认 `BYPASS`）
   - `CCLC_SKILLS_DIR`（可选，指向 `skills-root`，加载 `<name>/SKILL.md` 知识型 Skill）
2. `~/.claude-code-j/config.json`
   ```json
   {
     "apiKey": "...",
     "model": "claude-sonnet-4-6",
     "maxTokens": 8000,
     "permissionMode": "BYPASS"
   }
   ```

Skill 编写契约见 [docs/skill-authoring.md](docs/skill-authoring.md)。

**权限模式速查**

| 模式 | Read/Glob/Grep | Bash/Write/Edit | 适用 |
|---|---|---|---|
| `BYPASS`（默认）| ALLOW | ALLOW | 单机开发，自动化 |
| `DEFAULT` | ALLOW | ASK（交互确认）| 需要可控写操作 |
| `PLAN` | ALLOW | DENY（直接拒）| 只读规划阶段 |
| `AUTO` | ALLOW | ASK（safelist 外）| 同 DEFAULT，留作扩展 |

## 常用命令

```powershell
mvn clean verify                                                               # 编译 + 单测 + Failsafe IT + JaCoCo
mvn test                                                                       # 全模块单测
mvn -pl cclc-kernel -Dtest=AgentExecutorTest test                              # 单模块单类
mvn -pl cclc-kernel -Dtest=ConversationTest#appendsMessagesInOrder test        # 单方法
mvn -Psmoke "-Dsurefire.skip=true" verify                                      # 跑 *SmokeIT.java（需 API key）
mvn -q -pl cclc-cli -am test-compile exec:java                                 # 启动 REPL
mvn -q -pl cclc-cli -am test-compile exec:java "-Dexec.args=--version"
.\run.bat "-Dexec.args=--version"                                              # Windows 快捷启动
```

JaCoCo 报告：`cclc-*/target/site/jacoco/index.html`。

## REPL 用法

启动后输入即可对话。斜杠命令：

- `/help` 列出命令
- `/clear` 清空当前会话
- `/resume <sessionId>` 从 JSONL 恢复历史（不重跑工具）
- `Ctrl-C` 一次取消当前 turn；turn 结束后再按一次退出进程

## 测试覆盖

- 426 unit + ArchUnit，全绿（0 skip）
- 3 个 SmokeIT 真实 API 验证：纯对话 / 显式工具 / 隐式工具选择

## 目录速查

```
cclc-kernel/
  application         AgentExecutor、PermissionService、SystemPromptComposer、SessionResumer
  domain              Conversation、ChatMessage、Tool、PermissionPolicy、端口
  infrastructure      LLM、stream-json、memory、context、permission、通用 tools

cclc-agent-diagnosis/
  domain/diagnosis    DiagnosisCase、DiagnosisPlan、Evidence、EvidenceLedger
  application/diagnosis
  infrastructure      诊断工具、治理包装、planner/reporter/state codec
  interfaces/engine   DiagnoseEngine、RunRequest、DiagnoseEngineBuilder

cclc-cli/
  interfaces/cli      JLine REPL、斜杠命令、SIGINT、输出渲染
```

依赖边界：`cclc-agent-diagnosis → cclc-kernel`，`cclc-cli → cclc-kernel`；kernel 不感知诊断或 CLI，CLI 不进入宿主 classpath。
