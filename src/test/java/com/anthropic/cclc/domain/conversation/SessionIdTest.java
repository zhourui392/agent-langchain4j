package com.anthropic.cclc.domain.conversation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionIdTest {

    @Test
    void freshSessionsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(SessionId.fresh().value());
        }
        assertThat(ids).hasSize(1000);
    }

    @Test
    void freshSessionsAreTimeOrdered() throws InterruptedException {
        SessionId a = SessionId.fresh();
        Thread.sleep(5);
        SessionId b = SessionId.fresh();

        UUID uuidA = UUID.fromString(a.value());
        UUID uuidB = UUID.fromString(b.value());
        long tsA = uuidA.getMostSignificantBits() >>> 16;
        long tsB = uuidB.getMostSignificantBits() >>> 16;

        assertThat(tsA).isLessThanOrEqualTo(tsB);
    }

    @Test
    void freshUsesUuidV7Format() {
        SessionId fresh = SessionId.fresh();
        UUID parsed = UUID.fromString(fresh.value());
        assertThat(parsed.version()).isEqualTo(7);
    }

    @Test
    void ofRejectsBlankInput() {
        assertThatThrownBy(() -> SessionId.of(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofRejectsNullInput() {
        assertThatThrownBy(() -> SessionId.of(null))
                .isInstanceOf(NullPointerException.class);
    }
}
