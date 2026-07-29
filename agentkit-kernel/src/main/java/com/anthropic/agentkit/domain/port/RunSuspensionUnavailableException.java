package com.anthropic.agentkit.domain.port;

/** Uniform failure for absent, consumed, wrong-scope or wrong-kind resume credentials. */
public final class RunSuspensionUnavailableException extends RuntimeException {

    public RunSuspensionUnavailableException() {
        super("run suspension is unavailable for this resume request");
    }

    public RunSuspensionUnavailableException(Throwable cause) {
        super("run suspension is unavailable for this resume request", cause);
    }
}
