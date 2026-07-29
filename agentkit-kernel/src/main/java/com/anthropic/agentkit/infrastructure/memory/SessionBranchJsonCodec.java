package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.session.BranchOrigin;
import com.anthropic.agentkit.domain.session.BranchPoint;
import com.anthropic.agentkit.domain.session.RunEventPointer;
import com.anthropic.agentkit.domain.session.SessionBranch;
import com.anthropic.agentkit.domain.session.SessionBranchEvent;
import com.anthropic.agentkit.domain.session.SessionBranchId;
import com.anthropic.agentkit.domain.session.SessionBranchScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/** Explicit version-1 wire format for append-only branch facts. */
final class SessionBranchJsonCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SessionBranchJsonCodec() {
    }

    static String toJson(SessionBranchEvent event) throws IOException {
        if (!(event instanceof SessionBranchEvent.BranchCreated created)) {
            throw new IOException("unsupported session branch event");
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", created.schemaVersion());
        root.put("type", "branch_created");
        root.put("sequence", created.sequence());
        root.put("occurredAt", created.occurredAt().toString());
        root.set("branch", writeBranch(created.branch()));
        return root.toString();
    }

    static SessionBranchEvent fromJson(String line) throws IOException {
        JsonNode root = JSON.readTree(line);
        int version = requiredInt(root, "schemaVersion");
        if (version != SessionBranchEvent.CURRENT_SCHEMA_VERSION) {
            throw new IOException("unsupported session branch schema version: " + version);
        }
        if (!requiredText(root, "type").equals("branch_created")) {
            throw new IOException("unknown session branch event type");
        }
        return new SessionBranchEvent.BranchCreated(
                version, requiredLong(root, "sequence"),
                Instant.parse(requiredText(root, "occurredAt")),
                readBranch(required(root, "branch")));
    }

    private static ObjectNode writeBranch(SessionBranch branch) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", branch.id().value());
        node.put("sessionId", branch.scope().sessionId().value());
        node.put("workspaceId", branch.scope().workspaceId().value());
        node.put("origin", branch.origin().name());
        node.set("head", writePointer(branch.head()));
        branch.parentPoint().ifPresent(parent -> node.set("parent", writeParent(parent)));
        return node;
    }

    private static ObjectNode writeParent(BranchPoint parent) {
        ObjectNode node = JSON.createObjectNode();
        node.put("branchId", parent.branchId().value());
        node.set("event", writePointer(parent.event()));
        return node;
    }

    private static ObjectNode writePointer(RunEventPointer pointer) {
        ObjectNode node = JSON.createObjectNode();
        node.put("runId", pointer.runId().value());
        node.put("sequence", pointer.sequence());
        return node;
    }

    private static SessionBranch readBranch(JsonNode node) throws IOException {
        SessionBranchScope scope = new SessionBranchScope(
                SessionId.of(requiredText(node, "sessionId")),
                WorkspaceId.of(requiredText(node, "workspaceId")));
        Optional<BranchPoint> parent = readParent(node);
        return new SessionBranch(
                SessionBranchId.of(requiredText(node, "id")), scope,
                BranchOrigin.valueOf(requiredText(node, "origin")), parent,
                readPointer(required(node, "head")));
    }

    private static Optional<BranchPoint> readParent(JsonNode node) throws IOException {
        Optional<JsonNode> parent = optional(node, "parent");
        if (parent.isEmpty()) {
            return Optional.empty();
        }
        JsonNode value = parent.orElseThrow();
        return Optional.of(new BranchPoint(
                SessionBranchId.of(requiredText(value, "branchId")),
                readPointer(required(value, "event"))));
    }

    private static RunEventPointer readPointer(JsonNode node) throws IOException {
        return new RunEventPointer(
                RunId.of(requiredText(node, "runId")),
                requiredLong(node, "sequence"));
    }

    private static JsonNode required(JsonNode node, String field) throws IOException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            throw new IOException("missing session branch field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = required(node, field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IOException("invalid session branch field: " + field);
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode node, String field) throws IOException {
        JsonNode value = required(node, field);
        if (!value.canConvertToInt()) {
            throw new IOException("invalid session branch field: " + field);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode node, String field) throws IOException {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber()) {
            throw new IOException("invalid session branch field: " + field);
        }
        return value.longValue();
    }

    private static Optional<JsonNode> optional(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? Optional.empty() : Optional.of(value);
    }

}
