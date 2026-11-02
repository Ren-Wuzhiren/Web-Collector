package com.webcollector.capture.worker;

import com.webcollector.capture.dto.CaptureJobSummary;
import com.webcollector.capture.entity.CaptureJobStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    // [测试意图] 验证单条任务异常不会让同一轮调度继续消费后续任务。
    @Test
    void shouldStopBatchAfterUnexpectedWorkerFailure() {
        CaptureWorker worker = mock(CaptureWorker.class);
        when(worker.runOnce()).thenThrow(new IllegalStateException("failed"));
        CaptureWorkerScheduler scheduler = new CaptureWorkerScheduler(worker, 5);

        scheduler.processDueJobs();

        verify(worker).runOnce();
    }

    private CaptureJobSummary job() {
        return new CaptureJobSummary(
                1L,
                2L,
                CaptureJobStatus.SUCCEEDED,
                1,
                Instant.parse("2026-11-02T00:00:00Z")
        );
    }
}
