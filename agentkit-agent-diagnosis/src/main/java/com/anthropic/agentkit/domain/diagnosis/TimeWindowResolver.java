package com.anthropic.agentkit.domain.diagnosis;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Resolves a user time expression against host-supplied clock and timezone facts.
 *
 * @author alex
 */
public interface TimeWindowResolver {

    TimeResolution resolve(String userExpression, Instant now, ZoneId zoneId,
                           TimeWindowPolicy policy);
}
