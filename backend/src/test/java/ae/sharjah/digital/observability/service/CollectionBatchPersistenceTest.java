package ae.sharjah.digital.observability.service;

import ae.sharjah.digital.observability.domain.CollectionBatch;
import ae.sharjah.digital.observability.domain.MetricObservation;
import ae.sharjah.digital.observability.persistence.CollectionExecutionLogRepository;
import ae.sharjah.digital.observability.persistence.MetricSnapshotRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(CollectionBatchPersistenceTest.Config.class)
class CollectionBatchPersistenceTest {
    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean(destroyMethod = "shutdown") org.springframework.jdbc.datasource.embedded.EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                    .addScript("classpath:db/schema-h2.sql").build();
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean MetricSnapshotRepository snapshots(JdbcTemplate jdbc) { return new MetricSnapshotRepository(jdbc); }
        @Bean CollectionExecutionLogRepository logs(JdbcTemplate jdbc) { return new CollectionExecutionLogRepository(jdbc); }
        @Bean CollectionBatchPersistence persistence(MetricSnapshotRepository snapshots, CollectionExecutionLogRepository logs) {
            return new CollectionBatchPersistence(snapshots, logs);
        }
    }

    @Autowired CollectionBatchPersistence persistence;
    @Autowired JdbcTemplate jdbc;
    @Autowired CollectionExecutionLogRepository logs;

    @BeforeEach void clear() {
        jdbc.update("DELETE FROM OBS_METRIC_SNAPSHOT");
        jdbc.update("DELETE FROM OBS_COLLECTION_EXECUTION_LOG");
    }

    private MetricObservation observation(String metric, double value) {
        Instant end = Instant.parse("2026-01-01T00:01:00Z");
        return new MetricObservation("BIGQUERY", "WEB", "GA4_INTRADAY", metric, value, "events", end.minusSeconds(60), end, false);
    }

    @Test void invalidSecondObservationRollsBackEntireBatch() {
        CollectionBatch batch = new CollectionBatch("BIGQUERY", Arrays.asList(observation("a", 1), observation("b", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> persistence.persist("invalid", batch, 1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM OBS_METRIC_SNAPSHOT", Integer.class).intValue());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM OBS_COLLECTION_EXECUTION_LOG", Integer.class).intValue());
    }

    @Test void emptyAndPartialAreDistinguishedFromSuccess() {
        persistence.persist("empty", new CollectionBatch("BIGQUERY", Collections.emptyList()), 1);
        assertEquals("EMPTY", logs.findLatestSourceOutcomes().get(0).get("status"));
        jdbc.update("DELETE FROM OBS_COLLECTION_EXECUTION_LOG");
        persistence.persist("partial", new CollectionBatch("BIGQUERY", Collections.singletonList(observation("a", 1)), true), 1);
        assertEquals("PARTIAL", logs.findLatestSourceOutcomes().get(0).get("status"));
    }

    @Test void newerSourceCorrectionReplacesSameObservation() {
        MetricObservation initial = observation("a", 1);
        MetricObservation corrected = new MetricObservation(initial.id, initial.sourceSystem, initial.platform, initial.serviceName,
                initial.metricName, 2, initial.metricUnit, initial.windowStartUtc, initial.windowEndUtc, initial.collectedAtUtc.plusSeconds(1), false);
        persistence.persist("first", new CollectionBatch("BIGQUERY", Collections.singletonList(initial)), 1);
        persistence.persist("second", new CollectionBatch("BIGQUERY", Collections.singletonList(corrected)), 1);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM OBS_METRIC_SNAPSHOT", Integer.class).intValue());
        assertEquals(2.0, jdbc.queryForObject("SELECT METRIC_VALUE FROM OBS_METRIC_SNAPSHOT", Double.class).doubleValue(), 0.0);
    }
}
