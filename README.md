# Claude Code on LangChain4j

![CI](https://github.com/USER/REPO/actions/workflows/ci.yml/badge.svg)

Java + LangChain4j 实现的 claude-code 核心主流程（消息循环、工具调用、上下文管理、权限、流式输出），CLI 形态交付 MVP。

设计文档：[DESIGN.md](DESIGN.md)
任务清单：[TASKLIST.md](TASKLIST.md)

## 环境

- JDK 21
- Maven 3.9+
- 环境变量 `ANTHROPIC_API_KEY`（运行真实 API 时）

## 构建

```bash
mvn clean verify
```

## 运行

```bash
mvn exec:java
```
