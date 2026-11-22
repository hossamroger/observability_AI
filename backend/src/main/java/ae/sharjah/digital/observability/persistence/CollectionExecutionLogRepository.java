package ae.sharjah.digital.observability.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionExecutionLogRepository {
    private final JdbcTemplate jdbc;
    public CollectionExecutionLogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(String executionId, String source, String status, int collected, long durationMs,
                       String errorCategory, String message) {
        jdbc.update("INSERT INTO OBS_COLLECTION_EXECUTION_LOG "
                + "(EXECUTION_ID, SOURCE_SYSTEM, OPERATION_NAME, EXECUTED_AT_UTC, STATUS, RECORD_COUNT, "
                + "DURATION_MS, ERROR_CATEGORY, MESSAGE) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                executionId, source, "COLLECT_METRICS", Timestamp.from(Instant.now()),
                status, collected, durationMs, errorCategory, message);
    }
}
