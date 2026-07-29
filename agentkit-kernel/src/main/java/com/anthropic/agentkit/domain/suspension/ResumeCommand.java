package com.anthropic.agentkit.domain.suspension;

import java.util.Objects;

/** Typed host response used to claim and resume exactly one pending request. */
public sealed interface ResumeCommand permits
        ResumeCommand.Approval,
        ResumeCommand.Answer {

    ResumeToken token();

    SuspensionKind expectedKind();

    static ResumeCommand approve(ResumeToken token) {
        return new Approval(token, ApprovalDecision.APPROVE);
    }

    static ResumeCommand deny(ResumeToken token) {
        return new Approval(token, ApprovalDecision.DENY);
    }

    static ResumeCommand answer(ResumeToken token, String answer) {
        return new Answer(token, InputAnswer.of(answer));
    }

    record Approval(ResumeToken token, ApprovalDecision decision) implements ResumeCommand {
        public Approval {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(decision, "decision");
        }

        @Override public SuspensionKind expectedKind() { return SuspensionKind.APPROVAL; }
    }

    record Answer(ResumeToken token, InputAnswer answer) implements ResumeCommand {
        public Answer {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(answer, "answer");
        }

        @Override public SuspensionKind expectedKind() { return SuspensionKind.INPUT; }
    }
}
