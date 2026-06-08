# Claude `stream-json` 契约（agent-web 消费侧实测）

> @author zhourui(V33215020)
> @since 2026-06-08
>
> 这是引擎事件序列化（E1）必须对齐的**唯一权威契约**。
>
> 契约来源不是"跑一遍 claude CLI 抓样本"，而是 **agent-web 真正消费这些行的三处代码**——
> 它们才是引擎输出对不对的最终裁判：
> - `infra/cli/ClaudeCliDialect.java` —— `extractResumeId` / `normalizeChunk`(直通) / `isTurnEnd`
> - `resources/static/js/lib/formatters.js` —— `parseStreamJson`（历史回放渲染）
> - `resources/static/js/chat-panel.js` —— SSE `chunk` 事件实时渲染
>
> CLAUDE 在 agent-web 里是 **pass-through**：`normalizeChunk` 原样返回该行，所以引擎产出什么，前端就解析什么。

---

## 1. 前端实际分支的事件类型（这就是必须产出的全集）

前端按 **顶层 `type`** 分支，stream 增量再按 **`event.type`** 二级分支：

| 顶层 `type` | 二级 `event.type` | 前端动作 | 引擎钩子 |
|---|---|---|---|
| `stream_event` | `content_block_start`（`content_block.type=="tool_use"`） | 新建 tool 段，取 `content_block.name` | `onToolUseStart` |
| `stream_event` | `content_block_delta`（`delta.type=="text_delta"`） | 文本累加 `delta.text` | `onAssistantTextDelta` |
| `stream_event` | `content_block_delta`（`delta.type=="input_json_delta"`） | 追加 `delta.partial_json` 到当前 tool 段 | `onToolUseStart`（参数） |
| `stream_event` | `content_block_stop` | 结束当前 tool 段（`inToolUse=false`） | tool 段收尾 |
| `assistant` | —— | 仅当无流式内容时回退：读 `message.content[]`（`text` / `tool_use`） | （MVP 不产出，流式已覆盖） |
| `user` | —— | 读 `message.content[].tool_result`，结果文本取 `block.content`(string) 或 `tool_use_result` | `onToolUseEnd` |
| `result` | —— | `isTurnEnd==true`；无内容时把 `result` 当兜底文本 | `onTurnComplete` / `onError` |

**会话 id**：前端对**任意**含顶层 `session_id` 的行做 `if (json.session_id && !resumeId) resumeId = json.session_id`；
`ClaudeCliDialect.extractResumeId` 同样读**顶层** `session_id`。所以 `session_id` 必须出现在顶层（init 行 + result 行各带一次最稳）。

**turn 结束**：`ClaudeCliDialect.isTurnEnd` 认 `type=="result"`。引擎产出 `result` 行即代表本轮结束。

---

## 2. 引擎必须产出的逐行 JSON 形状（E1-1 ClaudeStreamJsonWriter 的目标）

> 一行一个 JSON object，无换行符内嵌（NDJSON）。未知顶层 `type`（如 `system`）前端 try/catch 后落空所有分支 → 安全忽略，仅用于回显 `session_id`。

### 2.1 会话开始（回显 session_id / cwd）
```json
{"type":"system","subtype":"init","session_id":"<sessionId>","cwd":"<workingDir>"}
```

### 2.2 文本增量
```json
{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"<delta>"}}}
```

### 2.3 工具调用开始（+ 参数增量）
```json
{"type":"stream_event","event":{"type":"content_block_start","content_block":{"type":"tool_use","id":"<id>","name":"<toolName>"}}}
{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"<argumentsJson 整串>"}}}
{"type":"stream_event","event":{"type":"content_block_stop"}}
```
> 引擎一次拿到完整工具入参，故用**单条** `input_json_delta` 承载整串 `argumentsJson`，前端把它累加进 tool 段即可正确显示。

### 2.4 工具结果
```json
{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"<id>","content":"<结果文本>"}]}}
```
> 前端取 `block.content`（string）。`tool_use_id` 用于 Anthropic 协议配对，前端不强依赖但保留。

### 2.5 本轮结束（成功）
```json
{"type":"result","subtype":"success","result":"<最终汇总文本>","session_id":"<sessionId>","usage":{"input_tokens":0,"output_tokens":0}}
```

### 2.6 本轮结束（出错）
```json
{"type":"result","subtype":"error_during_execution","result":"<错误信息>","session_id":"<sessionId>","is_error":true}
```
> 前端在 `result` 分支若无其它内容，会把 `result` 文本直接渲染，错误因此可见。

---

## 3. 与原 DESIGN/plan §2.2 的差异（已修正）

plan §2.2 原表把文本增量写成顶层 `content_block_delta` / `text_delta`，**实测错误**：
前端只认 `type=="stream_event"` 且读 `event.*`。没有 `stream_event` 外壳的增量行会被前端全部丢弃。
本文件以 agent-web 消费侧为准，plan §2.2 已据此更新。

## 4. 实时 vs 回放的细微差异（不影响引擎产出）

- `chat-panel.js`（实时 SSE）：`content_block_delta` 直接看 `delta.text` / `delta.partial_json`，不校验 `delta.type`。
- `formatters.js`（历史回放）：会校验 `delta.type=="text_delta"` / `"input_json_delta"`。

引擎按 §2 同时带上 `delta.type` 与对应字段，两条路径都满足。

## 5. 未做实时 CLI 抓样本的原因

`claude` CLI 抓样本依赖本机安装 + 真实 API 调用（有成本、可能挂起），且 CLI 版本会漂移；
而 agent-web 的解析代码是**确定性的最终消费方**。对齐它比对齐某次 CLI 输出更可靠，也是验收的实际标准
（E4 用 `DiagnoseFlowTest` 模式做 SSE 集成验证时，校验的就是前端能否解析）。
如需真·样本，后续可用 `claude --print --output-format stream-json --verbose --include-partial-messages` 补采，存为 `claude-stream-json-*.ndjson`。
