package com.anthropic.cclc.domain.conversation;

import java.util.Objects;

public final class TokenBudget {

    private static final double DEFAULT_THRESHOLD_RATIO = 0.85;

    private final int maxTokens;
    private final int thresholdTokens;
    private final TokenEstimator estimator;

    private TokenBudget(int maxTokens, double thresholdRatio, TokenEstimator estimator) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        this.maxTokens = maxTokens;
        this.thresholdTokens = (int) Math.floor(maxTokens * thresholdRatio);
        this.estimator = Objects.requireNonNull(estimator, "estimator");
    }

    public static TokenBudget of(int maxTokens) {
        return new TokenBudget(maxTokens, DEFAULT_THRESHOLD_RATIO, TokenEstimator.CHAR_HEURISTIC);
    }

    public static TokenBudget of(int maxTokens, TokenEstimator estimator) {
        return new TokenBudget(maxTokens, DEFAULT_THRESHOLD_RATIO, estimator);
    }

    public int maxTokens() {
        return maxTokens;
    }

    public int thresholdTokens() {
        return thresholdTokens;
    }

    public int estimate(String text) {
        return estimator.estimate(text);
    }

    public boolean thresholdReached(int currentTokens) {
        return currentTokens >= thresholdTokens;
    }
}
