/**
 * Ordered, typed lifecycle extension points for the provider-neutral agent loop.
 *
 * <p>Blocking callbacks return decisions and map callback failures to explicit run/tool
 * outcomes. Post callbacks are observers whose failures are isolated. Tool callbacks for
 * different invocations may run concurrently, while callbacks for one lifecycle event always
 * follow declaration order.</p>
 */
package com.anthropic.agentkit.application.interception;
