package ae.sharjah.digital.observability.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionExecutionLogRepository {
    private final JdbcTemplate jdbc;
    public CollectionExecutionLogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public java.util.List<java.util.Map<String, Object>> findLatestSourceOutcomes() {
        String sql = "SELECT SOURCE_SYSTEM, STATUS, ERROR_CATEGORY, RECORD_COUNT, EXECUTED_AT_UTC FROM "
                + "(SELECT l.*, ROW_NUMBER() OVER (PARTITION BY SOURCE_SYSTEM ORDER BY EXECUTED_AT_UTC DESC, EXECUTION_ID DESC) AS OBS_RANK "
                + "FROM OBS_COLLECTION_EXECUTION_LOG l WHERE EXECUTED_AT_UTC >= ?) WHERE OBS_RANK = 1 ORDER BY SOURCE_SYSTEM";
        return jdbc.query(sql, (row, index) -> {
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            String category = row.getString("ERROR_CATEGORY");
            String status = row.getString("STATUS");
            result.put("sourceSystem", row.getString("SOURCE_SYSTEM"));
            result.put("status", "EMPTY_COLLECTION".equals(category) ? "EMPTY" : "PARTIAL_COLLECTION".equals(category) ? "PARTIAL" : status);
            result.put("errorCategory", category);
            result.put("recordCount", row.getInt("RECORD_COUNT"));
            result.put("executedAtUtc", row.getTimestamp("EXECUTED_AT_UTC").toInstant().toString());
            return result;
        }, Timestamp.from(Instant.now().minusSeconds(86400)));
    }

    public void record(String executionId, String source, String status, int collected, long durationMs,
                       String errorCategory, String message) {
        jdbc.update("INSERT INTO OBS_COLLECTION_EXECUTION_LOG "
                + "(EXECUTION_ID, SOURCE_SYSTEM, OPERATION_NAME, EXECUTED_AT_UTC, STATUS, RECORD_COUNT, "
                + "DURATION_MS, ERROR_CATEGORY, MESSAGE) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                executionId, source, "COLLECT_METRICS", Timestamp.from(Instant.now()),
                status, collected, durationMs, errorCategory, message);
    }
}
