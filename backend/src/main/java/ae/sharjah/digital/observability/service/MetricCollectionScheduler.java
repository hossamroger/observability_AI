package ae.sharjah.digital.observability.service;

import ae.sharjah.digital.observability.connector.MonitoringConnector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Independent source dispatch. Run one collector replica until a distributed lease is implemented. */
@Component
@ConditionalOnProperty(name="monitoring.collector.enabled", havingValue="true")
public class MetricCollectionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(MetricCollectionScheduler.class);
    private final MetricCollectionService collection;
    private final TaskExecutor executor;
    private final Map<MonitoringConnector, SourceSchedule> schedules = new LinkedHashMap<>();

    public MetricCollectionScheduler(MetricCollectionService collection, List<MonitoringConnector> connectors,
            @Qualifier("monitoringCollectionExecutor") TaskExecutor executor,
            @Value("${monitoring.collection.interval-ms:30000}") long intervalMillis) {
        this.collection = collection;
        this.executor = executor;
        java.util.Set<String> sourceNames = new java.util.HashSet<>();
        for (MonitoringConnector connector : connectors) {
            if (!sourceNames.add(connector.sourceName())) throw new IllegalStateException("Duplicate monitoring source");
            schedules.put(connector, new SourceSchedule(Math.max(1000L, Math.max(intervalMillis, connector.minimumIntervalMillis()))));
        }
    }

    @Scheduled(fixedDelayString="${monitoring.collection.dispatch-interval-ms:1000}", initialDelay=3000)
    public void pollSources() {
        schedules.forEach((connector, schedule) -> {
            if (!schedule.tryAcquire(System.nanoTime())) return;
            try {
                executor.execute(() -> {
                    try { collection.collectSource(connector); }
                    finally { schedule.complete(System.nanoTime()); }
                });
            } catch (RuntimeException rejected) {
                schedule.complete(System.nanoTime());
                LOG.warn("event=collection_dispatch_rejected source={} category={}", connector.sourceName(), rejected.getClass().getSimpleName());
            }
        });
    }

    /** At most one task per source; delay measured from completion. */
    static final class SourceSchedule {
        private final long delayNanos;
        private boolean running;
        private boolean attempted;
        private long completedAt;

        SourceSchedule(long delayMillis) { this.delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMillis); }

        synchronized boolean tryAcquire(long now) {
            if (running || (attempted && now - completedAt < delayNanos)) return false;
            running = true;
            return true;
        }

        synchronized void complete(long now) {
            completedAt = now;
            attempted = true;
            running = false;
        }
    }
}
