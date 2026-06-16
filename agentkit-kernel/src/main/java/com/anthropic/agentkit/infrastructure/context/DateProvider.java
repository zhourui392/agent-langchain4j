package com.anthropic.agentkit.infrastructure.context;

import com.anthropic.agentkit.domain.context.ContextProvider;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

public final class DateProvider implements ContextProvider {

    private final Clock clock;

    public DateProvider() {
        this(Clock.systemDefaultZone());
    }

    public DateProvider(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String key() {
        return "date";
    }

    @Override
    public Optional<String> provide(Path workingDirectory) {
        return Optional.of(LocalDate.now(clock).toString());
    }

    @Override
    public boolean isDynamic() {
        return true;
    }
}
