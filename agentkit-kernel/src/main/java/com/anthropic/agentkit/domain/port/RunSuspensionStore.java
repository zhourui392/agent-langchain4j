package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.suspension.ResumeScope;
import com.anthropic.agentkit.domain.suspension.ResumeToken;
import com.anthropic.agentkit.domain.suspension.RunSuspension;
import com.anthropic.agentkit.domain.suspension.SuspensionKind;

/** Durable, atomic boundary for pending run requests and single-use token claims. */
public interface RunSuspensionStore {

    void save(RunSuspension suspension, ResumeToken token);

    RunSuspension claim(
            ResumeToken token, ResumeScope scope, SuspensionKind expectedKind);

    default boolean enabled() {
        return true;
    }

    static RunSuspensionStore none() {
        return Disabled.INSTANCE;
    }

    enum Disabled implements RunSuspensionStore {
        INSTANCE;

        @Override
        public void save(RunSuspension suspension, ResumeToken token) {
            throw new RunSuspensionStoreException("run suspension store is disabled", null);
        }

        @Override
        public RunSuspension claim(
                ResumeToken token, ResumeScope scope, SuspensionKind expectedKind) {
            throw new RunSuspensionUnavailableException();
        }

        @Override public boolean enabled() { return false; }
    }
}
