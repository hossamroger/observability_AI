package ae.sharjah.digital.observability.service;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetricCollectionSchedulerTest {
    @Test void slowSourceDoesNotPreventAnotherSourceFromBeingScheduled() {
        MetricCollectionScheduler.SourceSchedule slow = new MetricCollectionScheduler.SourceSchedule(300000);
        MetricCollectionScheduler.SourceSchedule fast = new MetricCollectionScheduler.SourceSchedule(30000);
        assertTrue(slow.tryAcquire(0));
        assertTrue(fast.tryAcquire(0));
        fast.complete(TimeUnit.SECONDS.toNanos(1));
        assertFalse(slow.tryAcquire(TimeUnit.SECONDS.toNanos(31)));
        assertTrue(fast.tryAcquire(TimeUnit.SECONDS.toNanos(31)));
    }

    @Test void sameSourceCannotOverlapAndDelayBeginsAfterCompletion() {
        MetricCollectionScheduler.SourceSchedule source = new MetricCollectionScheduler.SourceSchedule(300000);
        assertTrue(source.tryAcquire(0));
        assertFalse(source.tryAcquire(TimeUnit.MINUTES.toNanos(10)));
        source.complete(TimeUnit.MINUTES.toNanos(10));
        assertFalse(source.tryAcquire(TimeUnit.MINUTES.toNanos(14)));
        assertTrue(source.tryAcquire(TimeUnit.MINUTES.toNanos(15)));
    }
}
