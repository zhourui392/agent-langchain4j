package com.anthropic.agentkit.domain.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStateTest {

    @Test
    void activeStatesCanAdvanceButTerminalStatesCannotRegress() {
        assertThat(TaskState.STARTING.transitionTo(TaskState.RUNNING))
                .isEqualTo(TaskState.RUNNING);
        assertThat(TaskState.RUNNING.transitionTo(TaskState.COMPLETED))
                .isEqualTo(TaskState.COMPLETED);
        assertThat(TaskState.CANCELLED.transitionTo(TaskState.CANCELLED))
                .isEqualTo(TaskState.CANCELLED);

        assertThatThrownBy(() -> TaskState.COMPLETED.transitionTo(TaskState.RUNNING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TaskState.CANCELLED.transitionTo(TaskState.TIMED_OUT))
                .isInstanceOf(IllegalStateException.class);
    }
}
