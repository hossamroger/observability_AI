package ae.sharjah.digital.observability.service;

import ae.sharjah.digital.observability.connector.MonitoringConnector;
import ae.sharjah.digital.observability.domain.CollectionBatch;
import ae.sharjah.digital.observability.persistence.CollectionExecutionLogRepository;
import java.util.UUID;
import org.springframework.web.client.HttpStatusCodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MetricCollectionService {
    private static final Logger LOG = LoggerFactory.getLogger(MetricCollectionService.class);
    private final CollectionBatchPersistence batchPersistence;
    private final CollectionExecutionLogRepository executionLogs;
    private final LiveUpdatePublisher updates;

    public MetricCollectionService(CollectionBatchPersistence batchPersistence,
            CollectionExecutionLogRepository executionLogs,
            LiveUpdatePublisher updates) {
        this.batchPersistence = batchPersistence;
        this.executionLogs = executionLogs;
        this.updates = updates;
    }

    /** Failures are isolated by connector; errors are structured, and secrets are never logged. */
    public long collectSource(MonitoringConnector connector) {
        long retryAfterMillis = -1L; // Non-429 result; no rate-limit backoff.
        String executionId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        try {
            CollectionBatch batch = connector.collect();
            if (!connector.sourceName().equals(batch.sourceSystem))
                throw new IllegalStateException("Unexpected batch source");
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            batchPersistence.persist(executionId, batch, elapsedMs);
            String outcome = batch.observations.isEmpty() ? "EMPTY" : batch.partial ? "PARTIAL" : "SUCCESS";
            LOG.info("event=metrics_collection_completed execution_id={} source={} outcome={} count={} collection_duration_ms={}",
                    executionId, connector.sourceName(), outcome, batch.observations.size(), elapsedMs);
        } catch (Exception failure) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            String errorType = sanitizedErrorCategory(failure);
            retryAfterMillis = RateLimitRetry.afterMillis(failure, java.time.Instant.now());
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
        // Notification is best effort and must not change the persisted collection outcome.
        try { updates.broadcastSourceChange(connector.sourceName()); }
        catch (RuntimeException notificationFailure) { LOG.warn("event=collection_notification_failed source={}", connector.sourceName()); }
        return retryAfterMillis;
    }
    private String sanitizedErrorCategory(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ae.sharjah.digital.observability.connector.BigQueryCollectionException) {
                return ((ae.sharjah.digital.observability.connector.BigQueryCollectionException) cause).category();
            }
            if (cause instanceof HttpStatusCodeException) {
                return "HTTP_" + ((HttpStatusCodeException) cause).getRawStatusCode();
            }
            if (cause.getCause() == null) break;
            cause = cause.getCause();
        }
        return cause == null ? "UNKNOWN_ERROR" : cause.getClass().getSimpleName();
    }

}
