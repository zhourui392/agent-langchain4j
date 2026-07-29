/**
 * Infrastructure：从领域无关 AgentSpec 运行受约束角色和有生命周期的 child agent。
 * StructuredAgent 返回通用 payload；DefaultSubAgentRuntime 强制能力、预算、取消和并发边界；
 * 领域 VO 映射与工作流编排仍由各 agent 包负责。
 */
package com.anthropic.agentkit.infrastructure.agent;
