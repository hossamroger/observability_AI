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
        String sql = "INSERT INTO OBS_METRIC_SNAPSHOT "
                + "(METRIC_ID, SOURCE_SYSTEM, PLATFORM, SERVICE_NAME, METRIC_NAME, METRIC_VALUE, "
                + "METRIC_UNIT, WINDOW_START_UTC, WINDOW_END_UTC, COLLECTED_AT_UTC, IS_DEMO) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            jdbc.update(sql, observation.id, observation.sourceSystem, observation.platform,
                    observation.serviceName, observation.metricName, observation.metricValue,
                    observation.metricUnit, Timestamp.from(observation.windowStartUtc),
                    Timestamp.from(observation.windowEndUtc), Timestamp.from(observation.collectedAtUtc),
                    observation.demo ? 1 : 0);
        } catch (DuplicateKeyException overlappingSnapshot) {
            // Late source corrections replace the same observation, never add another count.
            jdbc.update("UPDATE OBS_METRIC_SNAPSHOT SET METRIC_VALUE = ?, METRIC_UNIT = ?, COLLECTED_AT_UTC = ? "
                    + "WHERE METRIC_ID = ? AND COLLECTED_AT_UTC < ?",
                    observation.metricValue, observation.metricUnit, Timestamp.from(observation.collectedAtUtc),
                    observation.id, Timestamp.from(observation.collectedAtUtc));
        }
    }

    public List<MetricObservation> findLatestPerMetric(boolean demo) {
        return findLatestPerMetric(demo, true);
    }

    public List<MetricObservation> findLatestPerMetric(boolean demo, boolean includeServiceMetrics) {
        String sql = "SELECT * FROM (SELECT s.*, ROW_NUMBER() OVER (PARTITION BY "
                + "SOURCE_SYSTEM, PLATFORM, SERVICE_NAME, METRIC_NAME ORDER BY WINDOW_END_UTC DESC, "
                + "COLLECTED_AT_UTC DESC, METRIC_ID DESC) AS OBS_RANK FROM OBS_METRIC_SNAPSHOT s "
                + "WHERE IS_DEMO = ? AND WINDOW_END_UTC >= ? "
                + (includeServiceMetrics ? "" : "AND SOURCE_SYSTEM <> 'DYNATRACE' ")
                + ") WHERE OBS_RANK = 1 "
                + "ORDER BY SOURCE_SYSTEM, PLATFORM, SERVICE_NAME, METRIC_NAME";
        return jdbc.query(sql, (result, row) -> new MetricObservation(
                result.getString("METRIC_ID"), result.getString("SOURCE_SYSTEM"), result.getString("PLATFORM"),
                result.getString("SERVICE_NAME"), result.getString("METRIC_NAME"),
                result.getDouble("METRIC_VALUE"), result.getString("METRIC_UNIT"),
                result.getTimestamp("WINDOW_START_UTC").toInstant(),
                result.getTimestamp("WINDOW_END_UTC").toInstant(),
                result.getTimestamp("COLLECTED_AT_UTC").toInstant(),
                result.getInt("IS_DEMO") == 1), demo ? 1 : 0,
                Timestamp.from(java.time.Instant.now().minusSeconds(3600)));
    }

    public List<MetricObservation> findServiceHistory(String serviceId, java.time.Instant start, java.time.Instant end, boolean demo) {
        String sql = "SELECT * FROM (SELECT s.*, ROW_NUMBER() OVER (PARTITION BY METRIC_NAME, WINDOW_END_UTC "
                + "ORDER BY COLLECTED_AT_UTC DESC, METRIC_ID DESC) AS OBS_RANK FROM OBS_METRIC_SNAPSHOT s "
                + "WHERE SOURCE_SYSTEM = 'DYNATRACE' AND PLATFORM = 'BACKEND' AND IS_DEMO = ? "
                + "AND (SERVICE_NAME = ? OR SERVICE_NAME LIKE ?) AND WINDOW_END_UTC > ? AND WINDOW_END_UTC <= ?) "
                + "WHERE OBS_RANK = 1 ORDER BY WINDOW_END_UTC, METRIC_NAME";
        return jdbc.query(sql, (result, row) -> new MetricObservation(
                result.getString("METRIC_ID"), result.getString("SOURCE_SYSTEM"), result.getString("PLATFORM"),
                result.getString("SERVICE_NAME"), result.getString("METRIC_NAME"),
                result.getDouble("METRIC_VALUE"), result.getString("METRIC_UNIT"),
                result.getTimestamp("WINDOW_START_UTC").toInstant(), result.getTimestamp("WINDOW_END_UTC").toInstant(),
                result.getTimestamp("COLLECTED_AT_UTC").toInstant(), result.getInt("IS_DEMO") == 1),
                demo ? 1 : 0, serviceId, "% [" + serviceId + "]", Timestamp.from(start), Timestamp.from(end));
    }

    public List<MetricObservation> findApplicationHistory(String platform, String metric, java.time.Instant start, java.time.Instant end, boolean demo) {
        String sql = "SELECT * FROM (SELECT s.*, ROW_NUMBER() OVER (PARTITION BY WINDOW_START_UTC, WINDOW_END_UTC "
                + "ORDER BY COLLECTED_AT_UTC DESC, METRIC_ID DESC) AS OBS_RANK FROM OBS_METRIC_SNAPSHOT s "
                + "WHERE SOURCE_SYSTEM = 'BIGQUERY' AND SERVICE_NAME = 'GA4_INTRADAY' AND PLATFORM = ? AND METRIC_NAME = ? "
                + "AND IS_DEMO = ? AND WINDOW_END_UTC > ? AND WINDOW_END_UTC <= ?) "
                + "WHERE OBS_RANK = 1 ORDER BY WINDOW_END_UTC FETCH FIRST 2000 ROWS ONLY";
        return jdbc.query(sql, (result, row) -> new MetricObservation(
                result.getString("METRIC_ID"), result.getString("SOURCE_SYSTEM"), result.getString("PLATFORM"),
                result.getString("SERVICE_NAME"), result.getString("METRIC_NAME"),
                result.getDouble("METRIC_VALUE"), result.getString("METRIC_UNIT"),
                result.getTimestamp("WINDOW_START_UTC").toInstant(), result.getTimestamp("WINDOW_END_UTC").toInstant(),
                result.getTimestamp("COLLECTED_AT_UTC").toInstant(), result.getInt("IS_DEMO") == 1),
                platform, metric, demo ? 1 : 0, Timestamp.from(start), Timestamp.from(end));
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
