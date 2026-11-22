package ae.sharjah.digital.observability.persistence;

import ae.sharjah.digital.observability.domain.MetricObservation;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
public class MetricSnapshotRepository {
    private final JdbcTemplate jdbc;
    public MetricSnapshotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(MetricObservation observation) {
        String sql = "MERGE INTO OBS_METRIC_SNAPSHOT target USING (SELECT ? METRIC_ID FROM DUAL) incoming ON (target.METRIC_ID = incoming.METRIC_ID) " +\n                "WHEN MATCHED THEN UPDATE SET target.METRIC_VALUE = ?, target.COLLECTED_AT_UTC = ? " +\n                "WHEN NOT MATCHED THEN INSERT " +\n                "(METRIC_ID, SOURCE_SYSTEM, PLATFORM, SERVICE_NAME, METRIC_NAME, METRIC_VALUE, METRIC_UNIT, WINDOW_START_UTC, WINDOW_END_UTC, COLLECTED_AT_UTC, IS_DEMO) " +\n                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"; */\n        /* String sql = "INSERT INTO OBS_METRIC_SNAPSHOT "
                + "(METRIC_ID, SOURCE_SYSTEM, PLATFORM, SERVICE_NAME, METRIC_NAME, METRIC_VALUE, "
                + "METRIC_UNIT, WINDOW_START_UTC, WINDOW_END_UTC, COLLECTED_AT_UTC, IS_DEMO) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            jdbc.update(sql, observation.id, observation.metricValue, Timestamp.from(observation.collectedAtUtc),\n                    observation.id, observation.sourceSystem, observation.platform,
                    observation.serviceName, observation.metricName, observation.metricValue,
                    observation.metricUnit, Timestamp.from(observation.windowStartUtc),
                    Timestamp.from(observation.windowEndUtc), Timestamp.from(observation.collectedAtUtc),
                    observation.demo ? 1 : 0);
        } catch (DuplicateKeyException overlappingSnapshot) {
            // A repeated source data point is not a new event; do not inflate counts.
        }
    }

    public List<MetricObservation> findLatest(int maximumRows) {
        int limit = Math.min(Math.max(maximumRows, 1), 500);
        // Portable SQL for Oracle 19c and H2: FETCH FIRST is supported by both.
        String sql = "SELECT * FROM OBS_METRIC_SNAPSHOT ORDER BY COLLECTED_AT_UTC DESC "
                + "FETCH FIRST " + limit + " ROWS ONLY";
        return jdbc.query(sql, (result, row) -> new MetricObservation(
                result.getString("METRIC_ID"), result.getString("SOURCE_SYSTEM"), result.getString("PLATFORM"),
                result.getString("SERVICE_NAME"), result.getString("METRIC_NAME"),
                result.getDouble("METRIC_VALUE"), result.getString("METRIC_UNIT"),
                result.getTimestamp("WINDOW_START_UTC").toInstant(),
                result.getTimestamp("WINDOW_END_UTC").toInstant(),
                result.getTimestamp("COLLECTED_AT_UTC").toInstant(),
                result.getInt("IS_DEMO") == 1));
    }
}
