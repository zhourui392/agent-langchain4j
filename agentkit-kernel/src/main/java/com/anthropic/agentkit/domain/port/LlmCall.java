package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.message.AiMessage;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** A single streaming provider request with one cancellable terminal outcome. */
public interface LlmCall {

    CompletionStage<AiMessage> completion();

    /** Best-effort provider cancellation. Returns true only when cancellation wins. */
    boolean cancel();

    static LlmCall start(LlmClient.StreamHandler handler,
                         Consumer<LlmClient.StreamHandler> starter) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(starter, "starter");
        ManagedCall call = new ManagedCall(handler);
        try {
            starter.accept(call.guardedHandler());
        } catch (Throwable failure) {
            call.guardedHandler().onError(failure);
        }
        return call;
    }

    static LlmCall completed(AiMessage message) {
        return start(LlmClient.StreamHandler.noop(), handler -> handler.onComplete(message));
    }

    final class ManagedCall implements LlmCall {
        private final LlmClient.StreamHandler downstream;
        private final CompletableFuture<AiMessage> completion = new CompletableFuture<>();
        private final Object lifecycleLock = new Object();
        private boolean terminal;
        private final LlmClient.StreamHandler guarded = new GuardedHandler();

        private ManagedCall(LlmClient.StreamHandler downstream) {
            this.downstream = downstream;
        }

        private LlmClient.StreamHandler guardedHandler() {
            return guarded;
        }

        @Override
        public CompletionStage<AiMessage> completion() {
            return completion;
        }

        @Override
        public boolean cancel() {
            synchronized (lifecycleLock) {
                if (terminal) {
                    return false;
                }
                terminal = true;
                completion.cancel(false);
                return true;
            }
        }

        private final class GuardedHandler implements LlmClient.StreamHandler {
            @Override
            public void onPartialText(String delta) {
                invokeNonTerminal(() -> downstream.onPartialText(delta));
            }

            @Override
            public void onUsage(int input, int output, int cacheRead) {
                invokeNonTerminal(() -> downstream.onUsage(input, output, cacheRead));
            }

            @Override
            public void onComplete(AiMessage message) {
                Objects.requireNonNull(message, "message");
                synchronized (lifecycleLock) {
                    if (terminal) {
                        return;
                    }
                    terminal = true;
                    try {
                        downstream.onComplete(message);
                        completion.complete(message);
                    } catch (Throwable failure) {
                        completion.completeExceptionally(failure);
                    }
                }
            }

            @Override
            public void onError(Throwable error) {
                Objects.requireNonNull(error, "error");
                synchronized (lifecycleLock) {
                    if (terminal) {
                        return;
                    }
                    terminal = true;
                    try {
                        downstream.onError(error);
                        completion.completeExceptionally(error);
                    } catch (Throwable callbackFailure) {
                        error.addSuppressed(callbackFailure);
                        completion.completeExceptionally(error);
                    }
                }
            }

            private void invokeNonTerminal(Runnable callback) {
                synchronized (lifecycleLock) {
                    if (terminal) {
                        return;
                    }
                    try {
                        callback.run();
                    } catch (Throwable failure) {
                        terminal = true;
                        completion.completeExceptionally(failure);
                    }
                }
            }
        }
    }
}
