package ae.sharjah.digital.observability.service;

import ae.sharjah.digital.observability.domain.CollectionBatch;
import ae.sharjah.digital.observability.domain.MetricObservation;
import ae.sharjah.digital.observability.persistence.CollectionExecutionLogRepository;
import ae.sharjah.digital.observability.persistence.MetricSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commit all observations and their outcome together, or roll back the entire batch. */
@Service
public class CollectionBatchPersistence {
    private final MetricSnapshotRepository snapshots;
    private final CollectionExecutionLogRepository logs;

    public CollectionBatchPersistence(MetricSnapshotRepository snapshots, CollectionExecutionLogRepository logs) {
        this.snapshots = snapshots;
        this.logs = logs;
    }

    @Transactional
    public void persist(String executionId, CollectionBatch batch, long collectionDurationMs) {
        for (MetricObservation metric : batch.observations) {
            if ((!batch.sourceSystem.equals(metric.sourceSystem) && !("DEMO".equals(batch.sourceSystem) && metric.demo))
                    || !Double.isFinite(metric.metricValue)
                    || metric.windowStartUtc.isAfter(metric.windowEndUtc))
                throw new IllegalArgumentException("Invalid observation in collection batch");
            snapshots.save(metric);
        }
        boolean empty = batch.observations.isEmpty();
        String category = empty ? "EMPTY_COLLECTION" : batch.partial ? "PARTIAL_COLLECTION" : null;
        String message = empty ? "Source returned no observations; coverage is unknown"
                : batch.partial ? "Some source queries failed; stored observations are incomplete"
                : "Collected observations successfully";
        logs.record(executionId, batch.sourceSystem, empty ? "FAILED" : "SUCCESS",
                batch.observations.size(), collectionDurationMs, category, message);
    }
}
