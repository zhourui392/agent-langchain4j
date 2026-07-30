package com.anthropic.agentkit.domain.diagnosis;

/**
 * Service names supplied at decreasing priority by user, page/session, and host defaults.
 *
 * @author alex
 */
public record ServiceSelection(String explicitService, String selectedService, String defaultService) {

    public ServiceSelection {
        explicitService = clean(explicitService);
        selectedService = clean(selectedService);
        defaultService = clean(defaultService);
    }

    public static ServiceSelection empty() {
        return new ServiceSelection("", "", "");
    }

    public static ServiceSelection hostDefault(String service) {
        return new ServiceSelection("", "", service);
    }

    public String preferredName() {
        if (!explicitService.isEmpty()) {
            return explicitService;
        }
        return !selectedService.isEmpty() ? selectedService : defaultService;
    }

    private static String clean(String value) {
        return SecretDataPolicy.sanitize(value);
    }
}
