package com.anthropic.agentkit.application.coding;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.coding.Patch;
import com.anthropic.agentkit.domain.coding.ReviewVerdict;
import com.anthropic.agentkit.domain.coding.CodingTask;

/**
 * Renders a terminal {@link ReviewVerdict} on a submitted {@link Patch}.
 *
 * <p>Reviewer role in the single-pass pipeline. The reviewer holds no write
 * capability — it only reads the patch and the task to decide ACCEPT / REJECT /
 * NEEDS_HUMAN, mirroring the hard capability boundary (allowedTools whitelist)
 * described in the project's multi-role principle.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-20
 */
public interface CodingReviewer {

    ReviewVerdict review(CodingTask task, Patch patch, AgentRunContext context);
}
