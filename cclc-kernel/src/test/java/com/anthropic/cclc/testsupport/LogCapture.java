package com.anthropic.cclc.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level originalLevel;
    private final boolean originalAdditive;
    private final CapturingAppender appender = new CapturingAppender();

    private LogCapture(Logger logger, Level level) {
        this.logger = logger;
        this.originalLevel = logger.getLevel();
        this.originalAdditive = logger.isAdditive();

        appender.start();
        logger.setLevel(level);
        logger.setAdditive(false);
        logger.addAppender(appender);
    }

    public static LogCapture forClass(Class<?> type, Level level) {
        return forLogger(type.getName(), level);
    }

    public static LogCapture forLogger(String loggerName, Level level) {
        return new LogCapture((Logger) LoggerFactory.getLogger(loggerName), level);
    }

    public List<ILoggingEvent> events() {
        return appender.events();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
        logger.setAdditive(originalAdditive);
        appender.stop();
    }

    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final java.util.List<ILoggingEvent> events = new java.util.ArrayList<>();

        @Override
        protected void append(ILoggingEvent eventObject) {
            eventObject.prepareForDeferredProcessing();
            events.add(eventObject);
        }

        private List<ILoggingEvent> events() {
            return List.copyOf(events);
        }
    }
}
