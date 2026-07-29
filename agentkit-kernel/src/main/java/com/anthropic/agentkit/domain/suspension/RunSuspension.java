package com.anthropic.agentkit.domain.suspension;

import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.util.List;
import java.util.Objects;

/** Durable pending request whose response must establish a new run segment. */
public sealed interface RunSuspension permits
        RunSuspension.WaitingForApproval,
        RunSuspension.WaitingForInput {

    SuspensionId id();

    SuspensionScope scope();

    SuspensionKind kind();

    StopReason stopReason();

    AiMessage finalMessage();

    record WaitingForApproval(
            SuspensionId id,
            SuspensionScope scope,
            ApprovalRequest request,
            AiMessage pendingAssistantMessage) implements RunSuspension {

        public WaitingForApproval {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(pendingAssistantMessage, "pendingAssistantMessage");
            List<ToolUseRequest> planned = request.invocations().stream()
                    .map(PlannedToolInvocation::request).toList();
            if (!planned.equals(pendingAssistantMessage.toolUseRequests())) {
                throw new IllegalArgumentException(
                        "approval plan must match the pending assistant tool batch");
            }
        }

        @Override public SuspensionKind kind() { return SuspensionKind.APPROVAL; }
        @Override public StopReason stopReason() { return StopReason.WAITING_FOR_APPROVAL; }
        @Override public AiMessage finalMessage() { return pendingAssistantMessage; }
    }

    record WaitingForInput(
            SuspensionId id,
            SuspensionScope scope,
            InputRequest request) implements RunSuspension {

        public WaitingForInput {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(request, "request");
        }

        @Override public SuspensionKind kind() { return SuspensionKind.INPUT; }
        @Override public StopReason stopReason() { return StopReason.WAITING_FOR_INPUT; }
        @Override public AiMessage finalMessage() { return AiMessage.text(request.prompt()); }
    }
}
