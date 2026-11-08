package com.webcollector.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 测试专用的 Logback 日志捕获器。
 *
 * <p>只变更测试期间目标 Logger 的 appender 列表，关闭时恢复原状。</p>
 */
public final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;

    private LogCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender
    ) {
        this.logger = logger;
        this.appender = appender;
    }

    public static LogCapture attach(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new LogCapture(logger, appender);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    public List<ILoggingEvent> containing(String text) {
        return events().stream()
                .filter(event -> event.getFormattedMessage().contains(text))
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
