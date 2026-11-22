package ae.sharjah.digital.observability.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Run exactly one scheduler replica until a distributed Oracle lease is implemented. */
@Component
@ConditionalOnProperty(name="monitoring.collector.enabled", havingValue="true")
public class MetricCollectionScheduler {
    private final MetricCollectionService collection;
    public MetricCollectionScheduler(MetricCollectionService collection) { this.collection = collection; }

    @Scheduled(fixedDelayString="${monitoring.collection.interval-ms:30000}", initialDelay=3000)
    public void pollSources() { collection.collectAllSources(); }
}
