package dev.kaiwen.eventpulse.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import dev.kaiwen.eventpulse.repository.SeedRunRepository;

class SeederServiceTest {

    @Test
    void seedsWithinTransactionAndRecordsTheRun() {
        SeedRunRepository runs = mock(SeedRunRepository.class);
        DemoDataSeeder demo = mock(DemoDataSeeder.class);
        when(runs.existsById("demo-v1")).thenReturn(false);
        SeederService service = new SeederService(runs, demo, "demo-v1");

        assertThat(service.seed()).isTrue();
        verify(demo).seed();
        // 完成记录与数据在同一个事务里写入；提交成功才会留下「已完成」。
        verify(runs).save(argThat(run -> "demo-v1".equals(run.getSeedName()) && run.getCompletedAt() != null));
    }

    @Test
    void completedVersionIsSkippedOnJobRetry() {
        SeedRunRepository runs = mock(SeedRunRepository.class);
        DemoDataSeeder demo = mock(DemoDataSeeder.class);
        when(runs.existsById("demo-v1")).thenReturn(true);
        SeederService service = new SeederService(runs, demo, "demo-v1");

        assertThat(service.seed()).isFalse();
        verify(demo, never()).seed();
        verify(runs, never()).save(argThat(run -> true));
    }

    @Test
    void failureRollsBackTheCompletionRecord() {
        SeedRunRepository runs = mock(SeedRunRepository.class);
        DemoDataSeeder demo = mock(DemoDataSeeder.class);
        when(runs.existsById("demo-v1")).thenReturn(false);
        doThrow(new IllegalStateException("seed blew up")).when(demo).seed();
        SeederService service = new SeederService(runs, demo, "demo-v1");

        assertThatThrownBy(service::seed).isInstanceOf(IllegalStateException.class);
        // 异常路径：save 从未被调用；即便调用了也会随事务回滚，Job 重试可以重新执行。
        verify(runs, times(0)).save(argThat(run -> true));
    }
}
