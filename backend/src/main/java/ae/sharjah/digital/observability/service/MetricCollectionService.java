package ae.sharjah.digital.observability.service;

import ae.sharjah.digital.observability.connector.MonitoringConnector;
import ae.sharjah.digital.observability.domain.CollectionBatch;
import ae.sharjah.digital.observability.domain.MetricObservation;
import ae.sharjah.digital.observability.persistence.CollectionExecutionLogRepository;
import ae.sharjah.digital.observability.persistence.MetricSnapshotRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.web.client.HttpStatusCodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MetricCollectionService {
    private static final Logger LOG = LoggerFactory.getLogger(MetricCollectionService.class);
    private final List<MonitoringConnector> connectors;
    private final java.util.Map<String, Long> lastAttemptNanos = new java.util.HashMap<>();
    private final MetricSnapshotRepository snapshots;
    private final CollectionExecutionLogRepository executionLogs;
    private final LiveUpdatePublisher updates;

    public MetricCollectionService(List<MonitoringConnector> connectors,
            MetricSnapshotRepository snapshots,
            CollectionExecutionLogRepository executionLogs,
            LiveUpdatePublisher updates) {
        this.connectors = connectors;
        this.snapshots = snapshots;
        this.executionLogs = executionLogs;
        this.updates = updates;
    }

    /** Failures are isolated by connector; errors are structured, and secrets are never logged. */
    public synchronized void collectAllSources() {
        for (MonitoringConnector connector : connectors) {
            long now = System.nanoTime();
            Long previous = lastAttemptNanos.get(connector.sourceName());
            if (previous != null && now - previous < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(connector.minimumIntervalMillis())) continue;
            lastAttemptNanos.put(connector.sourceName(), now);
            String executionId = UUID.randomUUID().toString();
            long started = System.nanoTime();
            try {
                CollectionBatch batch = connector.collect();
                for (MetricObservation metric : batch.observations) snapshots.save(metric);
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                executionLogs.record(executionId, connector.sourceName(), "SUCCESS",
                        batch.observations.size(), elapsedMs, null,
                        "Collected " + batch.observations.size() + " observations successfully");
                updates.broadcastSourceChange(connector.sourceName());
                LOG.info("event=metrics_collection_success execution_id={} source={} count={} duration_ms={}",
                        executionId, connector.sourceName(), batch.observations.size(), elapsedMs);
            } catch (Exception failure) {
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                String errorType = sanitizedErrorCategory(failure);
                // Never persist exception.toString() or vendor response bodies: they can contain secrets.
                try {
                    executionLogs.record(executionId, connector.sourceName(), "FAILED", 0,
                            elapsedMs, errorType,
                            "Unable to collect metrics from " + connector.sourceName()
                              + ". Failure category: " + errorType + ". Execution: " + executionId);
                } catch (Exception auditFailure) {
                    LOG.error("event=collection_log_failed source={} category={}",
                            connector.sourceName(), auditFailure.getClass().getSimpleName());
                }
                LOG.error("event=metrics_collection_failed execution_id={} source={} category={} duration_ms={}",
                        executionId, connector.sourceName(), errorType, elapsedMs);
            }
        }
    }
    private String sanitizedErrorCategory(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof HttpStatusCodeException) {
                return "HTTP_" + ((HttpStatusCodeException) cause).getRawStatusCode();
            }
            if (cause.getCause() == null) break;
            cause = cause.getCause();
        }
        return cause == null ? "UNKNOWN_ERROR" : cause.getClass().getSimpleName();
    }

}
