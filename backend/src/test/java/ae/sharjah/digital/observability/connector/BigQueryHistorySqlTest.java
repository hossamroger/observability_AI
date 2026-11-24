package ae.sharjah.digital.observability.connector;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BigQueryHistorySqlTest {
    @Test void buildsOneHistoricalQueryUsingIndependentBucketsAndSharedJourneyRules() {
        String sql = BigQueryGa4Connector.historySql(Arrays.asList(
                "example.analytics.events_20261124", "example.analytics.events_intraday_20261125"));
        assertTrue(sql.contains("FROM `example.analytics.events_20261124`"));
        assertTrue(sql.contains("FROM `example.analytics.events_intraday_20261125`"));
        assertTrue(sql.contains("DIV(event_timestamp-UNIX_MICROS(@start),@bucket_us)"));
        assertTrue(sql.contains("DIV(started_at-UNIX_MICROS(@start),@bucket_us)"));
        assertTrue(sql.contains("started_at < UNIX_MICROS(@end)-300000000"));
        assertTrue(sql.contains("GROUP BY platform,bucket_index"));
        assertTrue(sql.contains("APPROX_QUANTILES((home_at-started_at)/1000.0,100)"));
        assertTrue(sql.contains("WHERE m.metric_value IS NOT NULL"));
        assertEquals(1, sql.split("session_journeys AS", -1).length - 1);
    }
}
