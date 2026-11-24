package ae.sharjah.digital.observability.connector;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BigQueryPerformanceSqlTest {
    @Test void performanceUnitsAreExplicit() {
        assertEquals("ms", BigQueryPerformanceSql.metricUnit("session_to_home_p95_ms"));
        assertEquals("samples", BigQueryPerformanceSql.metricUnit("payment_to_purchase_samples"));
        assertEquals("sessions", BigQueryPerformanceSql.metricUnit("session_start_eligible_sessions"));
        assertEquals("events", BigQueryPerformanceSql.metricUnit("purchase_events"));
    }

    @Test void crossDayQueryUsesOneCombinedCohort() {
        String sql = BigQueryGa4Connector.sql(java.util.Arrays.asList(
                "example.analytics.events_20261124", "example.analytics.events_intraday_20261125"));
        assertTrue(sql.contains("FROM `example.analytics.events_20261124`"));
        assertTrue(sql.contains("FROM `example.analytics.events_intraday_20261125`"));
        assertEquals(2, sql.split("event_timestamp >= UNIX_MICROS", -1).length - 1);
        assertEquals(1, sql.split("session_journeys AS", -1).length - 1);
        assertTrue(sql.contains("workflow_total_events"));
    }

    @Test void queryRetainsScopeAndAvoidsUnmatchedPaymentDurations() {
        String sql = BigQueryGa4Connector.sql("example.analytics.events_intraday_20260101");
        assertTrue(sql.contains("stream_id=@ios"));
        assertTrue(sql.contains("screen_name=@home_screen"));
        assertTrue(sql.contains("initiation_times=1 AND purchase_times=1"));
        assertTrue(sql.contains("started_at < UNIX_MICROS(@end)-300000000"));
        assertTrue(sql.contains("APPROX_QUANTILES(duration_ms,100)"));
        assertTrue(sql.contains("UNION ALL SELECT platform,metric_name,metric_value,metric_scope FROM version_metrics"));
        assertTrue(sql.contains("app_info.version AS app_version"));
        assertTrue(sql.contains("version_rank<=20"));
        assertTrue(sql.contains("COUNTIF(duration_ms>60000)"));
        assertEquals("samples", BigQueryPerformanceSql.metricUnit("session_to_home_over_60s"));
    }
}
