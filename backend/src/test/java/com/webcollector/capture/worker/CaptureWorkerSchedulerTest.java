package com.webcollector.capture.worker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.webcollector.capture.dto.CaptureJobSummary;
import com.webcollector.capture.entity.CaptureJobStatus;
import com.webcollector.support.LogCapture;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class CaptureWorkerSchedulerTest {

    // [测试意图] 验证一次调度最多处理配置数量，并在空队列时提前停止。
    @Test
    void shouldProcessBoundedBatchAndStopWhenQueueIsEmpty() {
        CaptureWorker worker = mock(CaptureWorker.class);
        when(worker.runOnce()).thenReturn(
                Optional.of(job()),
                Optional.of(job()),
                Optional.empty()
        );
        CaptureWorkerScheduler scheduler = new CaptureWorkerScheduler(worker, 5);

        scheduler.processDueJobs();

        verify(worker, times(3)).runOnce();
    }

    // [测试意图] 验证非空批次只输出一条汇总日志，并区分成功与重试结果。
    @Test
    void shouldLogBatchSummaryAfterProcessingJobs() {
        CaptureWorker worker = mock(CaptureWorker.class);
        when(worker.runOnce()).thenReturn(
                Optional.of(job(CaptureJobStatus.SUCCEEDED)),
                Optional.of(job(CaptureJobStatus.PENDING)),
                Optional.empty()
        );
        CaptureWorkerScheduler scheduler = new CaptureWorkerScheduler(worker, 5);

        try (LogCapture logs = LogCapture.attach(
                CaptureWorkerScheduler.class
        )) {
            scheduler.processDueJobs();

            ILoggingEvent summary = onlyEvent(
                    logs,
                    "event=capture.batch.finished"
            );
            assertThat(summary.getLevel()).isEqualTo(Level.WARN);
            assertThat(summary.getThrowableProxy()).isNull();
            assertThat(summary.getFormattedMessage())
                    .contains(
                            "processed=2",
                            "succeeded=1",
                            "retried=1",
                            "unexpected=0"
                    )
                    .containsPattern("durationMs=\\d+");
        }
    }

    // [测试意图] 验证单条任务异常不会让同一轮调度继续消费后续任务。
    @Test
    void shouldStopBatchAfterUnexpectedWorkerFailure() {
        CaptureWorker worker = mock(CaptureWorker.class);
        when(worker.runOnce()).thenThrow(new IllegalStateException("failed"));
        CaptureWorkerScheduler scheduler = new CaptureWorkerScheduler(worker, 5);

        try (LogCapture logs = LogCapture.attach(
                CaptureWorkerScheduler.class
        )) {
            scheduler.processDueJobs();

            ILoggingEvent summary = onlyEvent(
                    logs,
                    "event=capture.batch.finished"
            );
            assertThat(summary.getLevel()).isEqualTo(Level.WARN);
            assertThat(summary.getThrowableProxy()).isNull();
            assertThat(summary.getFormattedMessage())
                    .contains("unexpected=1", "failureType=IllegalStateException")
                    .doesNotContain("IllegalStateException: failed");
        }
        verify(worker).runOnce();
    }

    // [测试意图] 验证空队列不会每秒输出空的批次摘要。
    @Test
    void shouldNotLogBatchSummaryForEmptyQueue() {
        CaptureWorker worker = mock(CaptureWorker.class);
        when(worker.runOnce()).thenReturn(Optional.empty());
        CaptureWorkerScheduler scheduler = new CaptureWorkerScheduler(worker, 5);

        try (LogCapture logs = LogCapture.attach(
                CaptureWorkerScheduler.class
        )) {
            scheduler.processDueJobs();

            assertThat(logs.containing("event=capture.batch.finished")).isEmpty();
        }
    }

    private CaptureJobSummary job() {
        return job(CaptureJobStatus.SUCCEEDED);
    }

    private CaptureJobSummary job(CaptureJobStatus status) {
        return new CaptureJobSummary(
                1L,
                2L,
                status,
                1,
                Instant.parse("2026-11-02T00:00:00Z")
        );
    }

    private ILoggingEvent onlyEvent(LogCapture logs, String eventName) {
        assertThat(logs.containing(eventName)).hasSize(1);
        return logs.containing(eventName).get(0);
    }
}
