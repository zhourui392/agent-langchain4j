# 接入 OpenAI 协议设计方案（方案二：LangChain4j OpenAI 适配器）

> @author zhourui(V33215020)
> 2026-06-13

## 1. 背景与目标

当前项目只支持 Anthropic：唯一工厂 `AnthropicLlmClientFactory` 写死
`AnthropicStreamingChatModel`，配置只认 `ANTHROPIC_API_KEY` / `CCLC_MODEL`。
`DESIGN.md §1.3 / §14.2` 把「多 provider 抽象」列为 out-of-scope。

本方案推翻该决策，引入 OpenAI 协议支持，并**默认切到 openai**，用中转站
`https://www.packyapi.com/v1` + `gpt-5.5` 做真实链路验证。

解耦的关键事实：`LangChain4jLlmClient` 只依赖 LangChain4j 通用接口 `StreamingChatModel`
（`LangChain4jLlmClient.java:23`），**不绑定 Anthropic**。因此接 OpenAI 真正要做的只是
①换一个 builder 造模型 ②provider 选择 ③配置项泛化。主循环 / 消息映射 / 工具 schema 全链路不动。

确认的决策：
| 项 | 取值 |
|---|---|
| 测试模型 | `gpt-5.5` |
| provider 选择 | **默认切到 openai** |
| key / baseUrl 命名 | **复用现有 + 通用别名**（openai 优先读通用名，回退 `ANTHROPIC_*`）|

## 2. 改动点

### 2.1 依赖（2 个 pom）

- `pom.xml`（parent）`<dependencyManagement>`：新增
  `dev.langchain4j:langchain4j-open-ai`，版本复用 `${langchain4j.version}`（1.8.0）。
- `cclc-kernel/pom.xml` `<dependencies>`：新增 `langchain4j-open-ai`，紧挨现有
  `langchain4j-anthropic`（`cclc-kernel/pom.xml:18-21`）。

### 2.2 配置模型（infrastructure/config）

- 新增枚举 `LlmProvider { ANTHROPIC, OPENAI }`。
- `AppConfig.java`：新增 `LlmProvider provider` 字段；保留现有兼容构造器（补默认 provider）。
- `ConfigLoader.java`：
  - **provider 解析**：`CCLC_PROVIDER` → file `provider` → 默认 `OPENAI`；非法值 fail-fast
    （复用 `resolvePermissionMode` 写法）。
  - **apiKey 别名链**：`CCLC_API_KEY` → `OPENAI_API_KEY` → `ANTHROPIC_API_KEY` → file `apiKey`。
  - **baseUrl 别名链**：`CCLC_BASE_URL` → `OPENAI_BASE_URL` → `ANTHROPIC_BASE_URL` → file `baseUrl`。
  - **provider 感知默认 model**：OPENAI→`gpt-5.5`，ANTHROPIC→`claude-sonnet-4-6`
    （`DEFAULT_MODEL` 拆成两个常量）。
  - 缺 key 错误信息改为通用文案（提及三个别名）。
  - 别名链用「按序取首个非空」小 helper，避免 if 嵌套超 3 层。

### 2.3 工厂抽象（infrastructure/llm）

- 新增接口 `LlmClientFactory`：`LangChain4jLlmClient create(AppConfig config)`。
- `AnthropicLlmClientFactory` → `implements LlmClientFactory`（签名已吻合，几乎零改）。
- 新增 `OpenAiLlmClientFactory implements LlmClientFactory`：
  - 用 `dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()`
    `.apiKey().baseUrl(...).modelName().maxTokens().timeout(60s).logRequests/Responses(true)`。
  - `baseUrl` 走 `config.baseUrlIfPresent().ifPresent(builder::baseUrl)`（同 Anthropic 工厂）。
  - 无 prompt-cache 概念，不接 `CacheBreakpointStrategy`。
  - **gpt-5.5 注意**：OpenAI 新模型要求 `max_completion_tokens` 且 temperature 只能默认。
    若 `.maxTokens()` 被拒，改用 `.maxCompletionTokens(...)`，不显式 set temperature。
- 新增选择器 `LlmClientFactories.create(AppConfig)`：按 `config.provider()` switch；
  Anthropic 走 `withCacheEnabled()`，OpenAI 走 `new OpenAiLlmClientFactory()`。

### 2.4 组装根（cclc-cli）

- `CclcApplication.java:82`：
  `AnthropicLlmClientFactory.withCacheEnabled().create(config)` → `LlmClientFactories.create(config)`。
  仅此一行。

### 2.5 不动的部分（已确认）

- `LangChain4jLlmClient`：provider 中立，无需改；token usage 的
  `instanceof AnthropicTokenUsage`（`LangChain4jLlmClient.java:100`）对 OpenAI 自动降级为 0。
- `AgentExecutor` / `MessageMapper` / `ToolSpecificationMapper` / ArchUnit 分层：全部不动。

## 3. 测试（TDD，红→绿）

- **改 `ConfigLoaderTest`**：现有断言假设 anthropic 默认（`missingApiKeyFailsFast` 断言含
  "ANTHROPIC_API_KEY"、`loadsApiKeyFromEnvironmentOnly` 断言 `DEFAULT_MODEL`），随新默认更新。
  新增：provider 默认 OPENAI、`CCLC_PROVIDER` 覆盖、key/baseUrl 别名优先级、
  OPENAI 默认 model=gpt-5.5、非法 provider fail-fast。
- **新增 `LlmClientFactoriesTest`**：给定 provider=OPENAI/ANTHROPIC 的 `AppConfig`，
  断言 `create()` 返回非 null 的 `LangChain4jLlmClient`（建模型不联网，纯单测）。
- **新增 `OpenAiEndToEndSmokeIT`**：镜像 `EndToEndSmokeIT.java`，
  `@EnabledIfEnvironmentVariable(named="CCLC_API_KEY")`，指向 packyapi + gpt-5.5，断言返回非空文本；
  归到 `-Psmoke`。

## 4. 文档（项目规则要求）

- `DESIGN.md §16`：记一条带日期（2026-06-13）决策——multi-provider 由 out-of-scope 转为支持，
  说明动机与边界（OpenAI 路径不支持 prompt cache / cache token 统计）。
- `README.md` 与 `CLAUDE.md`：更新「不在范围内」措辞，补 `CCLC_PROVIDER` / 通用别名说明。

## 5. 验证

1. 单测：`mvn -pl cclc-kernel test`（改后的 ConfigLoaderTest、新 LlmClientFactoriesTest 全绿）。
2. 回归：`mvn -pl cclc-kernel test`（ArchUnit + 426 unit 不破）。
3. 真实链路（packyapi + gpt-5.5）——临时环境变量，**不落盘明文 key**：
   ```powershell
   $env:CCLC_PROVIDER="openai"
   $env:CCLC_API_KEY="sk-..."          # 用户提供
   $env:CCLC_BASE_URL="https://www.packyapi.com/v1"
   $env:CCLC_MODEL="gpt-5.5"
   mvn -pl cclc-kernel -Dtest=OpenAiEndToEndSmokeIT -Psmoke "-Dsurefire.skip=true" verify
   ```
   或启 REPL 实测一问一答 + 一次工具调用：
   `mvn -q -pl cclc-cli -am test-compile exec:java`
4. 通过判据：smoke 返回非空文本无 error；REPL 能完成一次 `Read`/`Bash` 工具往返
   （验证 OpenAI 协议下 tool_use/tool_result 全链路）。

## 6. 风险

- gpt-5.5 的 `max_completion_tokens` / temperature 限制（见 §2.3，验证步骤兜底）。
- 中转站对流式 usage（`stream_options.include_usage`）支持差异——拿不到 usage 不影响主流程，
  仅 token 统计显示 0。
- 默认切 openai 后，原 anthropic 用户需显式 `CCLC_PROVIDER=anthropic`——已在文档说明。
