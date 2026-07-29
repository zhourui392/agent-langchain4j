package com.anthropic.agentkit.domain.agent;

import com.anthropic.agentkit.domain.tool.ExecutionContext;

/** Domain service boundary for starting bounded, independently cancellable child agents. */
public interface SubAgentRuntime {

    SubAgentHandle spawn(AgentSpec spec, String task, ExecutionContext parentContext);

    default SubAgentHandle spawn(
            AgentSpec spec, String task, AgentRunContext parentContext) {
        if (parentContext == null) {
            throw new NullPointerException("parentContext");
        }
        return spawn(spec, task, parentContext.executionContext());
    }
}
