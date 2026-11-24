package ae.sharjah.digital.observability.connector;

import ae.sharjah.digital.observability.domain.MetricObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BigQueryAggregateValidatorTest {
    private MetricObservation metric(String scope, String name, double value) {
        Instant end = Instant.parse("2026-01-01T00:30:00Z");
        return new MetricObservation("BIGQUERY", "IOS", scope, name, value, "source-defined",
                end.minusSeconds(1800), end, false);
    }

    @Test void validCohortAndSeparateVersionScopesAreAccepted() {
        assertDoesNotThrow(() -> BigQueryAggregateValidator.validate(Arrays.asList(
                metric("GA4_INTRADAY", "session_start_eligible_sessions", 100),
                metric("GA4_INTRADAY", "session_to_first_screen_samples", 90),
                metric("GA4_INTRADAY", "session_to_home_samples", 80),
                metric("APP_VERSION:1.0", "session_to_first_screen_samples", 5),
                metric("APP_VERSION:1.0", "session_to_home_samples", 4))));
    }

    @Test void rejectsImpossibleCountsAndDuplicateRows() {
        assertThrows(BigQueryCollectionException.class, () -> BigQueryAggregateValidator.validate(Arrays.asList(
                metric("GA4_INTRADAY", "session_to_home_samples", 91),
                metric("GA4_INTRADAY", "session_to_first_screen_samples", 90))));
        assertThrows(BigQueryCollectionException.class, () -> BigQueryAggregateValidator.validate(Arrays.asList(
                metric("GA4_INTRADAY", "observed_events", 2), metric("GA4_INTRADAY", "observed_events", 2))));
    }

    @Test void rejectsReversedPercentilesAndThresholdCounts() {
        assertThrows(BigQueryCollectionException.class, () -> BigQueryAggregateValidator.validate(Arrays.asList(
                metric("GA4_INTRADAY", "session_to_home_p50_ms", 5000),
                metric("GA4_INTRADAY", "session_to_home_p95_ms", 4000))));
        assertThrows(BigQueryCollectionException.class, () -> BigQueryAggregateValidator.validate(Arrays.asList(
                metric("GA4_INTRADAY", "session_to_home_over_60s", 9),
                metric("GA4_INTRADAY", "session_to_home_over_30s", 8))));
    }

    @Test void malformedRealtimeCountCannotBecomeZero() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertEquals(0.0, Ga4RealtimeConnector.parseCount(json.readTree("\"0\"")), 0.0);
        assertThrows(IllegalArgumentException.class, () -> Ga4RealtimeConnector.parseCount(json.readTree("{}").path("missing")));
        assertThrows(IllegalArgumentException.class, () -> Ga4RealtimeConnector.parseCount(json.readTree("\"invalid\"")));
        assertThrows(IllegalArgumentException.class, () -> Ga4RealtimeConnector.parseCount(json.readTree("\"1.5\"")));
    }
}
