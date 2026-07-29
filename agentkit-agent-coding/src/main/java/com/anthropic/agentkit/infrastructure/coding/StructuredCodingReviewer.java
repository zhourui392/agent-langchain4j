package com.anthropic.agentkit.infrastructure.coding;

import com.anthropic.agentkit.application.coding.CodingReviewer;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.coding.CodingTask;
import com.anthropic.agentkit.domain.coding.Patch;
import com.anthropic.agentkit.domain.coding.ReviewVerdict;
import com.anthropic.agentkit.domain.coding.Verdict;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.infrastructure.agent.StructuredAgent;
import com.anthropic.agentkit.infrastructure.agent.TerminalToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reviewer role: delegates the constrained-turn boilerplate to the kernel
 * {@link StructuredAgent} and owns only the role config plus payload-to-VO
 * mapping. The constructor accepts no domain tools by design — the hard
 * capability boundary (the reviewer can read and judge but never write) is
 * enforced by the type, not by trusting the prompt.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-20
 */
public final class StructuredCodingReviewer implements CodingReviewer {

    private static final Logger log = LoggerFactory.getLogger(StructuredCodingReviewer.class);

    private static final String TOOL_NAME = "submit_review";
    private static final String REVIEW_SCHEMA = """
            {"type":"object","properties":{\
            "decision":{"type":"string","enum":["ACCEPT","REJECT","NEEDS_HUMAN"]},\
            "summary":{"type":"string"},\
            "issues":{"type":"array"}\
            },"required":["summary"]}""";
    private static final String SYSTEM_PROMPT =
            "Review the patch against the requirement and render a verdict by "
                    + "calling the submit_review tool. Do not modify any files.";
    private static final TerminalToolSpec REVIEW_OUTPUT = new TerminalToolSpec(
            TOOL_NAME, "Submit the structured review verdict for the patch", REVIEW_SCHEMA);

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public StructuredCodingReviewer(LlmClient llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    @Override
    public ReviewVerdict review(CodingTask task, Patch patch, AgentRunContext context) {
        long startNs = System.nanoTime();
        StructuredAgent agent = new StructuredAgent(llm, SYSTEM_PROMPT, REVIEW_OUTPUT, List.of());
        Map<String, Object> payload = agent.run(buildTask(task, patch), context);
        ReviewVerdict verdict = toVerdict(payload);
        log.info("review verdict: taskId={}, decision={}, issues={}, durationMs={}",
                task.taskId(), verdict.decision(), verdict.issues().size(), elapsedMs(startNs));
        return verdict;
    }

    private static String buildTask(CodingTask task, Patch patch) {
        return "Review the patch for requirement: " + task.requirement()
                + "\nPatch summary: " + patch.summary()
                + "\nChanged files: " + patch.changes().size();
    }

    private ReviewVerdict toVerdict(Map<String, Object> payload) {
        ReviewDto dto = mapper.convertValue(payload, ReviewDto.class);
        return new ReviewVerdict(decision(dto.decision()), safeText(dto.summary()), safeList(dto.issues()));
    }

    private static Verdict decision(String decision) {
        return decision == null || decision.isBlank()
                ? Verdict.NEEDS_HUMAN
                : Verdict.valueOf(decision);
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> safeList(List<T> items) {
        return items == null ? List.of() : items;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private record ReviewDto(String decision, String summary, List<String> issues) {
    }
}
