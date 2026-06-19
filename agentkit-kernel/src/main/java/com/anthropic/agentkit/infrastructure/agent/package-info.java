/**
 * Infrastructure：受约束的专家 Agent 运行时（StructuredAgent SPI）。
 * 把 "建终结工具 -> 塞 registry -> 跑 executor -> 读 sink" 四步样板收进 kernel；
 * 返回通用 payload，领域 VO 映射由调用方负责。
 */
package com.anthropic.agentkit.infrastructure.agent;
