package io.wisoft.ignoa_payment.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level originalLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> type, Level level) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        this.originalLevel = logger.getLevel();
        logger.setLevel(level);
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture at(Class<?> type, Level level) {
        return new LogCapture(type, level);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }
}
