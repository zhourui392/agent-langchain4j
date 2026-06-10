package com.anthropic.cclc.domain.conversation;

@FunctionalInterface
public interface TokenEstimator {

    int estimate(String text);

    TokenEstimator CHAR_HEURISTIC = text -> text == null || text.isEmpty() ? 0 : text.length() / 4;
}
