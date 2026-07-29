package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.agent.AgentEntryPoint;
import com.anthropic.agentkit.domain.coding.CodingTask;

/** Stable host-facing entry point for the coding agent. */
public interface CodingEngine extends AgentEntryPoint<CodingRequest, CodingTask> {

    @Override
    default Class<CodingRequest> requestType() {
        return CodingRequest.class;
    }

    @Override
    default Class<CodingTask> resultType() {
        return CodingTask.class;
    }
}
