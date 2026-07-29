package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.application.coding.CodingPipeline;
import com.anthropic.agentkit.domain.coding.CodingTask;

import java.util.Objects;

/** Default coding entry point backed by the coding workflow. */
final class DefaultCodingEngine implements CodingEngine {

    private final CodingPipeline pipeline;

    DefaultCodingEngine(CodingPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    @Override
    public CodingTask invoke(CodingRequest request) {
        Objects.requireNonNull(request, "request");
        return pipeline.run(
                CodingTask.open(request.taskId(), request.requirement()), request.context());
    }
}
