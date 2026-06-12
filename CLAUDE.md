# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

Java 21 + LangChain4j reimplementation of `claude-code`'s core CLI loop (message turn, tool calling, context management, permissions, streaming). Reference implementation lives at `D:\ai_worspace\claude-code\src\` (TypeScript). The `## 15 与 claude-code 源码的对照速查` table in `DESIGN.md` maps each TS file to its Java counterpart — consult it before introducing new abstractions.

Two living documents drive the work:
- `DESIGN.md` — architecture, abstractions, decided trade-offs (sections 16 records dated decisions).
- `TASKLIST.md` — 39 numbered tasks across S0–S8 + MVP-Gate, with explicit Red/Green/Refactor steps and `blockedBy` dependencies. **Treat the task IDs as the unit of work** — when picking up new work, find the lowest-numbered unfinished task whose dependencies are met.

## Commands

Maven 3.9+ and JDK 21 required. Set `ANTHROPIC_API_KEY` env var to run against real API.

```powershell
mvn clean verify                                  # compile + unit tests + failsafe IT + jacoco
mvn test                                          # unit tests only (surefire)
mvn -Dtest=ConversationTest test                  # single test class
mvn -Dtest=ConversationTest#appendsMessagesInOrder test   # single test method
mvn verify -Psmoke                                # runs *SmokeIT.java (needs ANTHROPIC_API_KEY; skipped in CI)
mvn exec:java                                     # launch CclcApplication REPL
mvn exec:java -Dexec.args="--version"             # CLI flags
```

JaCoCo report lands in `target/site/jacoco/index.html` after `verify`. CI runs `mvn -B -ntp clean verify` on push/PR (`.github/workflows/ci.yml`).

## Architecture: DDD four-layer, ArchUnit-enforced

```
interfaces/cli  →  application  →  domain  ←  infrastructure
```

Dependency direction is enforced by `src/test/java/com/anthropic/cclc/arch/LayeredArchitectureTest.java`. Violations fail the build. In particular:

- **Domain must not import LangChain4j, infrastructure, application, or interfaces.** All outside-world coupling goes through ports in `domain/port/` (`LlmClient`, `ChatMemoryStore`).
- **Application must not import infrastructure or interfaces.** It orchestrates domain via ports; concrete bindings happen in `CclcApplication.main`.
- The whole package graph must be cycle-free (`noCyclesInLayers`).

Layer responsibilities (mirroring `DESIGN.md` §2):

- **interfaces/cli** — JLine REPL (`ReplLoop`), `SlashCommandParser`, `OutputRenderer`, `SigintHandler`. Terminal I/O, slash-command dispatch, streaming render.
- **application** — `AgentExecutor` (the heart — main turn loop), `PermissionService`, `SystemPromptComposer`, `SessionResumer`, `ParallelToolDispatcher`, `InvocationFactory`. Use-case orchestration; **no business rules**, **no infra dependencies**.
- **domain** — aggregates `Conversation` / `ToolInvocation`, sealed `ChatMessage` hierarchy, `Tool` interface, `PermissionPolicy`, ports `LlmClient` / `ChatMemoryStore`. Pure, zero external deps.
- **infrastructure** — `LangChain4jLlmClient` (Anthropic streaming), tool implementations (`BashTool`, `FileEditTool`, `GlobTool`, `GrepTool`, …), `FileChatMemoryStore` (JSONL), context providers, `DefaultPermissionPolicy`. Implements domain ports.

Composition root: `CclcApplication.main` wires everything manually — no Spring/Guice. New components must be added to the wiring there.

## The agent main loop

`application.AgentExecutor.run(conversation, cancel)` is the equivalent of `claude-code`'s `QueryEngine.streamLoop`. Each turn:

1. Compose system prompt via `SystemPromptComposer` (stable prefix for prompt cache).
2. Stream LLM response via `LlmClient` port — partial tokens go to `StreamHandler.onPartialText`.
3. Append assistant `AiMessage` to conversation. If no `toolUseRequests` → break.
4. Each tool call passes through `PermissionService.check` (ALLOW/ASK/DENY) before execution.
5. Tools dispatched via `ParallelToolDispatcher` (virtual threads) but `ToolResultMessage`s are appended **in original `tool_use` order** — this invariant is non-negotiable (Anthropic API requires paired ordering).
6. `CancellationToken` is checked at every loop start and inside the streaming handler; on cancel the loop unwinds gracefully.

## Non-obvious invariants

- **Conversation pairing**: `tool_use` and `tool_result` must pair up. `ToolUseInvariantChecker` enforces this on every `Conversation.append`. Test before adding new message flows.
- **FileStateCache read-before-write**: `FileEditTool` and `FileWriteTool` (on existing files) refuse to operate unless the file was previously read by `FileReadTool` in this session. The cache also tracks `mtime` to detect external modification. Bypassing this guard breaks the claude-code parity.
- **Prompt cache breakpoints**: `SystemPromptComposer` produces a stable prefix (`SYSTEM_INSTRUCTIONS` → CLAUDE.md → tool descriptions) before dynamic context. `CacheBreakpointStrategy` (in infra `llm/`) inserts the ephemeral cache markers. Do not insert dynamic content (date, git status) into the prefix — it kills cache hit rate.
- **Shell selection**: `BashTool` delegates to `ShellSelector` which picks `cmd.exe` on Windows and `bash -c` elsewhere. Tests must run cross-platform.
- **Grep backend**: `GrepTool` uses ripgrep when `rg --version` succeeds at startup, otherwise falls back to `JavaRegexGrepBackend`. Both backends are tested.
- **Session storage**: `FileChatMemoryStore` writes JSONL to `~/.claude-code-j/sessions/<id>.jsonl` (path via `SessionPaths`). `/resume <id>` reloads message history **without re-running tool calls** — `tool_use` and `tool_result` are persisted as data only.

## TDD discipline (project rule, not optional)

Tests are deliverable assets. Every task in `TASKLIST.md` flagged `[TDD]` must follow Red → Green → Refactor in three commits — no batching, no jumping straight to implementation. Function size cap is **50 lines**, nesting cap is **3 levels** (see global coding standards). When a method approaches the cap, extract a private helper (precedent: `AgentExecutor.run` extracted `executeTurn` / `dispatchToolCalls`).

`StubLlmClient` and `FakeTool` (in `src/test/java/.../testsupport/`) are the canonical seams for unit-testing the executor without hitting real APIs. Reuse them rather than introducing new mocks.

## Things explicitly out of scope (do not add)

Per `DESIGN.md` §1.3 and §14.2: no Spring/Guice, no Lombok, no multi-provider LLM abstraction (Anthropic only), no Ink-like rich terminal UI, no multimodal input (text only for MVP), and no IDE bridge/plugin subsystem. Skill support is knowledge-only per `DESIGN.md` §16.4; do not add script execution or automatic Bash enablement through skills. Configuration is env vars + `~/.claude-code-j/config.json` — no annotations, no reflection-based DI.
