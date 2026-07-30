package com.anthropic.agentkit.domain.diagnosis;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolution outcome with stable visible candidates for remediation or user selection.
 *
 * @author alex
 */
public record ServiceResolution(ServiceResolutionStatus status, String requestedName,
                                List<ServiceRef> candidates) {

    public ServiceResolution {
        status = Objects.requireNonNull(status, "status");
        requestedName = SecretDataPolicy.sanitize(requestedName);
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        validate(status, candidates);
    }

    public static ServiceResolution notConfigured() {
        return new ServiceResolution(ServiceResolutionStatus.NOT_CONFIGURED, "", List.of());
    }

    public static ServiceResolution resolved(String requestedName, ServiceRef service) {
        return new ServiceResolution(
                ServiceResolutionStatus.RESOLVED, requestedName, List.of(service));
    }

    public static ServiceResolution ambiguous(List<ServiceRef> candidates) {
        return new ServiceResolution(ServiceResolutionStatus.AMBIGUOUS, "", candidates);
    }

    public static ServiceResolution unknown(String requestedName, List<ServiceRef> candidates) {
        return new ServiceResolution(ServiceResolutionStatus.UNKNOWN, requestedName, candidates);
    }

    public Optional<ServiceRef> resolvedService() {
        return status == ServiceResolutionStatus.RESOLVED
                ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private static void validate(ServiceResolutionStatus status, List<ServiceRef> candidates) {
        if (status == ServiceResolutionStatus.RESOLVED && candidates.size() != 1) {
            throw new IllegalArgumentException("resolved service requires exactly one candidate");
        }
        if (status == ServiceResolutionStatus.AMBIGUOUS && candidates.size() < 2) {
            throw new IllegalArgumentException("ambiguous service requires multiple candidates");
        }
        if (status == ServiceResolutionStatus.NOT_CONFIGURED && !candidates.isEmpty()) {
            throw new IllegalArgumentException("unconfigured catalog cannot expose candidates");
        }
    }
}
