# Skill 机制实现设计（Skill Subsystem Design）

> @author zhourui(V33215020)
> @since 2026-06-13
> 状态：待评审。落地前需在 `DESIGN.md` §16 追加 dated decision（见 §1.2 草案），推翻原 out-of-scope 决策。

---

## 1. 背景与决策前提

### 1.1 现状

- `DESIGN.md` §1.3 将 Skill 子系统列为 out of scope；`docs/diagnosis-agent-capability-design.md` §5.1（2026-06-11 决策）以 PromptPack 替代：每个场景一份 markdown SOP，`SystemPromptComposer` 全量拼入 system prompt。
- PromptPack 的局限在引擎接入更多业务场景后暴露：
  1. **无条件注入**：所有 SOP 不论本次诊断是否用到，都占 system prompt token；场景增多后线性膨胀。
  2. **无选择能力**：模型无法按问题特征挑选 SOP，只能靠宿主在 `promptPacks(Path)` 装配期预选目录。
  3. **无层级结构**：SOP 引用的附属资料（表结构说明、错误码对照表）只能内联，进一步放大注入体积。
- 该决策文档同时预留了反悔通道：「若未来确需引入 Skill 子系统，必须先在 DESIGN.md 第 16 节记录推翻原决策的 dated decision」。本设计即该通道的执行产物。

### 1.2 DESIGN.md §16.4 决策草案（评审通过后合入）

```markdown
### 16.4 引入 Skill 子系统（2026-06-13）

推翻 §1.3 中「Skill 子系统 out of scope」与 capability-design §5.1 中「MVP 不引入可执行 Skill 机制」两项决策。
动机：PromptPack 全量注入随场景数线性膨胀，模型无按需取用能力。完整方案见 docs/skill-mechanism-design.md。

| 议题 | 决策 | 影响 |
|---|---|---|
| Skill 形态 | 目录 + SKILL.md（YAML frontmatter），对齐 claude-code Agent Skills | 渐进披露三级：目录注入 name+description，调用时返回正文，附属文件按需 Read |
| 归属模块 | kernel 通用件（domain.skill + infrastructure.skill），诊断层仅装配 | 第二个专用 Agent 直接复用；kernel 不出现 diagnosis 语义 |
| 能力边界 | **知识型 Skill**，不含可执行脚本 | 与只读诊断引擎姿态一致；Bash 不注册，脚本型 Skill 列入 P2 |
| PromptPack 去留 | 保留，二者分工：常驻必读走 PromptPack，按需取用走 Skill | 存量 SOP 渐进迁移，不做一次性切换 |
```

---

## 2. 目标与非目标

### 2.1 目标（MVP）

1. 模型可在对话中按需取用诊断知识：目录级感知（低 token 成本）→ 调用时全文展开。
2. Skill 以文件目录交付，宿主（agent-web）只需挂目录，不改 Java 代码即可增删 Skill。
3. 复用既有抽象落地：`Tool`、`ContextProvider`、`ToolRegistry`、`GovernedTool` 治理链，不新增框架。
4. 不破坏 prompt cache：Skill 目录进 stable prefix，Skill 正文以 tool_result 形式进入对话流。
5. 严格遵守 DDD 分层与 ArchUnit 规则，TDD 红绿重构交付。

### 2.2 非目标

- 不支持可执行脚本型 Skill（引擎只读姿态，Bash 不注册）—— P2。
- 不支持 Skill 热更新（引擎无状态、装配期固定）—— P1 评估。
- 不支持 `model` 按 Skill 覆盖、`context: fork` 子代理隔离 —— P2。
- 不做 plugin 市场、远程 Skill 仓库、版本管理。
- CLI 的 `/skill-name` 用户态调用 —— P1（CLI 仅为调试壳，优先级低）。

---

## 3. 参照：claude-code 的 Skill 机制要点

设计对齐 claude-code（TypeScript 参考实现）的 Agent Skills，取其三个不变量：

| 机制 | claude-code 行为 | 本项目映射 |
|---|---|---|
| **Skill 形态** | 目录 `<name>/SKILL.md` + 附属文件；frontmatter 含 `name`/`description`/`allowed-tools` 等 | 同构，MVP frontmatter 只取 `name`/`description`，其余字段解析但忽略（向前兼容） |
| **渐进披露 L1** | 启动时仅 name+description 注入上下文（每条几十 token） | `SkillCatalogContextProvider`（static）注入 stable prefix |
| **渐进披露 L2** | 模型经 `Skill` 工具调用，正文作为 tool_result 展开 | `SkillTool implements Tool`，`isReadOnly()==true` |
| **渐进披露 L3** | 正文引用的附属文件由模型用 Read 工具按需读取 | 复用既有 `FileReadTool`，Skill 根目录须在可读范围内 |
| **触发方式** | 模型按 description 自主匹配 + 用户 `/name` 显式触发 | MVP 仅模型自主匹配；`/name` 走 P1 |

与 claude-code 的有意分歧：

1. **不支持脚本执行**。claude-code 的 Skill 可携带脚本经 Bash 运行；本引擎是只读诊断姿态（§16.1 决策），`BashTool` 不注册，Skill 限定为知识载体。这与 capability-design §5「Skill 承载知识流程、Tool 承载执行能力」的边界一致。
2. **无多级发现目录**（user/project/plugin 三级合并）。引擎为进程内 jar，宿主在装配期给定单一 Skill 根目录即可，冲突合并规则随之省略。

---

## 4. 总体设计

### 4.1 模块归位

```
cclc-kernel
├── domain/skill/                    ← 纯领域，零外部依赖
│   ├── Skill.java                   （值对象：name/description/body/baseDir）
│   ├── SkillCatalog.java            （聚合：注册、查找、目录渲染，不变量收口）
│   └── SkillSource.java             （port：装配期加载入口，infra 实现）
├── infrastructure/skill/
│   ├── DirectorySkillSource.java    （扫描 <root>/<name>/SKILL.md，实现 SkillSource）
│   ├── SkillFrontmatterParser.java  （frontmatter 解析，jackson-dataformat-yaml）
│   └── SkillCatalogContextProvider.java （L1：目录注入 system prompt）
├── infrastructure/tools/
│   └── SkillTool.java               （L2：name="Skill"，返回正文）

cclc-agent-diagnosis
└── interfaces/engine/
    └── DiagnoseEngineBuilder.skills(Path) （装配入口，可选）
```

依赖方向不变：`domain.skill` 不 import 任何外层；`SkillTool`、`DirectorySkillSource` 依赖 domain；装配在 builder / `CclcApplication.main` 显式完成。无 classpath 扫描、无反射——目录文件扫描沿用 `PromptPackLoader` 先例。

### 4.2 运行时序（一次 Skill 取用）

```text
装配期
  DirectorySkillSource.load(root) → List<Skill> → SkillCatalog
  SkillCatalog → SkillCatalogContextProvider（注册进 SystemPromptComposer providers）
  SkillCatalog → SkillTool（注册进 ToolRegistry，经 GovernedTool 包装）

运行期
  1. SystemPromptComposer.compose() → stable prefix 含 "## skills" 目录段
  2. 模型读 description 判定需要某 Skill → 发起 tool_use {name:"Skill", input:{skill:"es-slow-query"}}
  3. ReadOnlyPermissionPolicy：SkillTool.isReadOnly()==true → ALLOW
  4. SkillTool.execute → SkillCatalog.find("es-slow-query") → ToolResult.ok(正文 + baseDir 标注)
  5. 正文作为 tool_result 进入对话流（prompt cache 前缀不变）
  6. 正文引用附属文件时，模型用 FileReadTool 读 <baseDir>/references/xxx.md
```

---

## 5. 领域模型

### 5.1 `Skill` 值对象

```java
public record Skill(String name, String description, String body, Path baseDir) {
    // 紧凑构造器收口不变量（工厂校验，对齐 Aggregate.create 判据）：
    // - name 匹配 ^[a-z0-9][a-z0-9-]{0,63}$，且与目录名一致
    // - description 非空，≤1024 字符（L1 注入成本上限）
    // - body 非空，≤64KB（L2 展开成本上限）
}
```

校验放领域而非 loader：loader 只做 IO 与解析，「什么是合法 Skill」是领域规则。非法 Skill 在装配期 fail-fast（抛 `IllegalArgumentException` 带文件路径），不静默跳过——静默跳过会让宿主以为 Skill 已生效。

### 5.2 `SkillCatalog` 聚合

```java
public final class SkillCatalog {
    public static SkillCatalog of(List<Skill> skills);  // name 冲突 → fail-fast
    public Optional<Skill> find(String name);
    public boolean isEmpty();
    public String renderCatalog();   // L1 目录文本，见 §7.1
    public List<String> names();     // SkillTool 错误提示用
}
```

语义查询（`find`）、目录渲染规则、冲突判定全部收在聚合内，app/infra 层不得对 `List<Skill>` 做 getter 重组（对齐 App 层泄漏判据）。

### 5.3 `SkillSource` port

```java
public interface SkillSource {
    List<Skill> load();
}
```

domain 定义、infra 实现，签名只含 domain 类型。装配期调用一次，结果不可变——与「引擎无状态」决策（§16.1）一致，运行中不感知文件变更。

---

## 6. SKILL.md 文件契约

```
<skills-root>/
├── es-slow-query/
│   ├── SKILL.md
│   └── references/
│       └── index-mapping-cheatsheet.md
└── trade-refund-trace/
    └── SKILL.md
```

SKILL.md 结构：

```markdown
---
name: es-slow-query
description: ES 慢查询诊断 SOP。当用户反馈 ES 查询超时、took 偏高、
  搜索接口 P99 恶化时使用。覆盖 profile 分析、分片倾斜、mapping 误用排查。
---

# ES 慢查询诊断

## 排查步骤
1. 用 EsTool 取 _cat/indices 确认目标索引健康度 ...
2. ...

详细 mapping 对照见 references/index-mapping-cheatsheet.md（用 Read 工具读取）。
```

frontmatter 规则：

| 字段 | 必填 | MVP 行为 |
|---|---|---|
| `name` | 否 | 缺省取目录名；显式给出时必须与目录名一致，否则 fail-fast |
| `description` | 是 | L1 注入的唯一触发依据，须写清「做什么 + 何时用」 |
| 其余字段（`allowed-tools`/`model` 等） | — | 解析不报错、忽略生效（为 P1/P2 预留，旧引擎读新 Skill 不炸） |

解析选型：`jackson-dataformat-yaml`（2.18.2，与既有 Jackson 同版本管理）。frontmatter 虽简单，手写解析器要处理多行折叠、引号转义等 YAML 细节，违反「禁止重造轮子」约定。

---

## 7. 渐进披露三级落地

### 7.1 L1：目录注入（`SkillCatalogContextProvider`）

```java
public final class SkillCatalogContextProvider implements ContextProvider {
    public String key() { return "skills"; }
    public Optional<String> provide(Path workingDirectory) {
        return catalog.isEmpty() ? Optional.empty() : Optional.of(catalog.renderCatalog());
    }
    // isDynamic() 默认 false → 进 stable prefix
}
```

渲染格式（`SkillCatalog.renderCatalog()`）：

```text
以下 Skill 可通过 Skill 工具按需展开。仅当 description 与当前问题匹配时调用；
调用一次即获得完整操作指引，禁止凭记忆复述未展开的 Skill 内容。

- es-slow-query: ES 慢查询诊断 SOP。当用户反馈 ES 查询超时…
- trade-refund-trace: 退款链路追踪 SOP。当…
```

放 stable prefix 的依据：目录在装配期固定、会话内不变，符合 `SystemPromptComposer` 的 static provider 语义，且可被 prompt cache 覆盖。成本预算：每条目录项约 60–120 token，装配期超过 50 个 Skill 时 builder 打 WARN 日志（不阻断）。

### 7.2 L2：`SkillTool`

```java
public final class SkillTool implements Tool {
    public String name() { return "Skill"; }
    public String description() {
        return "展开一个 Skill 获取完整操作指引。仅当 skills 目录中某条 description 与当前任务匹配时调用。";
    }
    public String inputSchema() {
        // {"type":"object","properties":{"skill":{"type":"string",
        //   "description":"目录中列出的 skill 名称"}},"required":["skill"]}
    }
    public boolean isReadOnly() { return true; }
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        // find 命中 → ok；未命中 → error("unknown skill: x. available: [...]")
    }
}
```

成功结果格式（baseDir 标注供 L3 引用解析）：

```text
# Skill: es-slow-query
# base: D:\skills\es-slow-query
# 引用文件以 base 为相对根，用 Read 工具读取。

<SKILL.md 正文（frontmatter 之后的全部内容）>
```

要点：

- **以 tool_result 展开而非改写 system prompt**：对话流追加不影响已缓存前缀；这也是 claude-code 的展开方式。
- `isReadOnly()==true` → `ReadOnlyPermissionPolicy` 直接 ALLOW，诊断引擎无需新增权限分支。
- 与其他工具同样经 `GovernedTool` 包装（超时、脱敏、审计），Skill 正文若含敏感样例同样过 redaction。
- 未命中返回 `ToolResult.error` 并列出可用名称，让模型自纠错，不抛异常中断 run。

### 7.3 L3：附属文件按需读取

复用 `FileReadTool`（已注册、`isReadOnly()==true`），不新增工具。约束：

- Skill 附属文件必须落在该 Skill 的 `baseDir` 下；SKILL.md 正文用相对路径引用。
- `DirectorySkillSource` 装配期不递归校验引用存在性（正文是给模型的自然语言，无可靠解析点）；引用失效由模型在 Read 失败时自行反馈，属可接受降级。
- 宿主若配置了文件读取路径白名单（治理层），需把 skills root 纳入。

---

## 8. 装配与配置

### 8.1 kernel 提供件

kernel 暴露三个可组装件，不预设装配方式：

```java
SkillCatalog catalog = SkillCatalog.of(new DirectorySkillSource(root, parser).load());
ContextProvider skillsProvider = new SkillCatalogContextProvider(catalog);
Tool skillTool = new SkillTool(catalog);
```

### 8.2 诊断层装配（`DiagnoseEngineBuilder`）

```java
DiagnoseEngineBuilder.create()
    .llm(llmClient)
    .toolBackends(backends, governance, truncator)
    .promptPacks(promptPackDir)      // 既有，保留
    .skills(skillsRoot)              // 新增，可选；缺省不启用 Skill
    .build();
```

`skills(Path)` 内部完成：load → catalog → 注册 `SkillTool` 进 ToolRegistry（过治理链）→ 注册 provider 进 composer。目录不存在或含非法 Skill → build 抛异常（fail-fast，宿主启动期暴露）。

### 8.3 CLI 装配

`CclcApplication.main` 同样以显式 wiring 接入，读取 `CCLC_SKILLS_DIR` 环境变量（缺省不启用）。CLI 仅调试用，`/skill-name` 斜杠触发不进 MVP。

---

## 9. 与 PromptPack 的边界

| 维度 | PromptPack | Skill |
|---|---|---|
| 注入时机 | 装配期全量拼入 system prompt | L1 仅目录，正文按需展开 |
| 适用内容 | 全场景必读：只读纪律、证据优先原则、输出格式契约 | 单场景 SOP：特定业务链路排查步骤、领域背景 |
| token 特征 | 常驻成本，随内容线性增长 | 常驻成本仅目录行，正文用到才付费 |
| 选择主体 | 宿主装配期预选 | 模型运行期按 description 匹配 |

迁移路径：存量 `prompts/diagnosis/` 中场景专属 SOP 逐个转为 Skill 目录；通用纪律类内容留在 PromptPack。两机制长期并存，判据一条：**每次诊断都必须读的留 PromptPack，其余下放 Skill**。

---

## 10. 安全与治理

| 风险 | 对策 |
|---|---|
| Skill 目录被植入越权指令（prompt injection 面扩大） | Skill 根目录由宿主治理（发布审核、目录只读挂载）；引擎侧 L1 渲染明示「Skill 是参考知识，不得覆盖系统级只读纪律」 |
| 正文含敏感信息（内网地址、样例 token） | `SkillTool` 过 `GovernedTool` redaction 链，与其他工具结果同等脱敏 |
| 路径逃逸（`name` 含 `../`） | `Skill` 不变量限定 name 字符集 `[a-z0-9-]`；baseDir 由 loader 以 root resolve + normalize 后校验仍在 root 内 |
| 超大 SKILL.md 拖垮上下文 | body ≤64KB 装配期硬限；运行期再过既有大结果截断（E6） |
| name 冲突导致取错 SOP | `SkillCatalog.of` 冲突 fail-fast |
| frontmatter 畸形 | 解析失败 fail-fast 并带文件路径，宿主启动期发现 |

---

## 11. 测试策略（按层）

| 层 | 对象 | Mock | 测试重点 |
|---|---|---|---|
| Domain | `Skill` | 无 | name 格式/长度/与目录一致性、description 必填、body 上限——全部不变量 |
| Domain | `SkillCatalog` | 无 | 冲突 fail-fast、find 命中/未命中、renderCatalog 格式、isEmpty |
| Infrastructure | `SkillFrontmatterParser` | 无（字符串输入） | 正常/缺 description/畸形 YAML/无 frontmatter/多行折叠 |
| Infrastructure | `DirectorySkillSource` | JUnit `@TempDir` | 目录扫描、name 缺省取目录名、非法目录 fail-fast、路径逃逸拒绝 |
| Infrastructure | `SkillTool` | stub catalog | 命中返回正文+base 标注、未命中 error 列名称、isReadOnly |
| Infrastructure | `SkillCatalogContextProvider` | stub catalog | 空目录 Optional.empty、非空渲染、isDynamic==false |
| Interfaces | `DiagnoseEngineBuilder.skills` | `StubLlmClient`/`FakeTool` | 装配后 ToolRegistry 含 Skill、composer 含目录段、非法目录 build 失败 |
| 端到端 | `AgentExecutor` + `StubLlmClient` | 脚本化 LLM | 模型发起 Skill tool_use → tool_result 含正文 → 二轮可继续（pairing 不变量不破坏） |

ArchUnit：`LayeredArchitectureTest` 无需新增规则，`domain.skill` 自动落入既有 domain 约束；新增包后跑一次确认无环。

---

## 12. TDD 任务拆解

对齐 TASKLIST.md 风格，每任务 Red → Green → Refactor 三步交付。

| 任务 | 内容 | blockedBy |
|---|---|---|
| SK-0 | DESIGN.md §16.4 决策合入 + §1.3 删除 Skill 条目 + CLAUDE.md out-of-scope 同步 | 评审通过 |
| SK-1 [TDD] | `Skill` 值对象 + 不变量 | SK-0 |
| SK-2 [TDD] | `SkillCatalog` 聚合（of/find/renderCatalog/冲突） | SK-1 |
| SK-3 [TDD] | `SkillFrontmatterParser`（引入 jackson-dataformat-yaml） | SK-0 |
| SK-4 [TDD] | `DirectorySkillSource`（扫描 + 路径逃逸防护 + fail-fast） | SK-1, SK-3 |
| SK-5 [TDD] | `SkillCatalogContextProvider` | SK-2 |
| SK-6 [TDD] | `SkillTool` | SK-2 |
| SK-7 [TDD] | `DiagnoseEngineBuilder.skills(Path)` 装配 + `CclcApplication` 环境变量接入 | SK-4, SK-5, SK-6 |
| SK-8 | 端到端：StubLlmClient 脚本走通「目录→调用→展开→续轮」；ArchUnit 全绿 | SK-7 |
| SK-9 | 文档收尾：README「不在范围内」更新、capability-design §5.1 标注决策已被 §16.4 推翻、Skill 编写指南（docs/skill-authoring.md） | SK-8 |

预估：SK-1～SK-6 均为纯单测任务，单任务 ≤0.5 天；SK-7/SK-8 合计 1 天。

---

## 13. 风险与权衡

| 权衡点 | 取舍 | 理由 |
|---|---|---|
| 展开方式：tool_result vs 注入 system prompt | tool_result | 保 prompt cache；claude-code 同构；注入 prefix 会让每次展开都 cache miss |
| 校验位置：loader vs 领域 | 领域不变量 | loader 只管 IO；合法性是领域规则，避免 infra 代劳 |
| 非法 Skill：跳过 vs fail-fast | fail-fast | 静默跳过 = 宿主误以为生效，诊断知识缺失比启动失败更难排查 |
| YAML 解析：手写 vs jackson-yaml | jackson-yaml | 多行折叠/转义细节多，手写违反禁止重造轮子；同版本族零额外治理成本 |
| 热更新 | 不做 | 引擎无状态、装配期固定是既有决策；宿主重启即生效，P1 再评估 mtime reload |
| `allowed-tools` 强制 | 解析忽略 | 诊断引擎全员只读，约束收益为零；通用场景需要时走 P1 |

## 14. 演进路线

- **P1**：CLI `/skill-name` 显式触发；多 Skill root 合并（含冲突优先级）；`allowed-tools` 接入 `PermissionPolicy`；mtime 热加载评估。
- **P2**：脚本型 Skill（依赖引擎放开非只读姿态，需独立安全评审）；`context: fork` 子代理隔离（依赖 `SubAgentTool` 扩展）；`model` 按 Skill 覆盖。
