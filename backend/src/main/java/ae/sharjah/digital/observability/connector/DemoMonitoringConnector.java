package ae.sharjah.digital.observability.connector;

import ae.sharjah.digital.observability.domain.CollectionBatch;
import ae.sharjah.digital.observability.domain.MetricObservation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicit demo data; never present these observations as production telemetry. */
@Component
@ConditionalOnProperty(name="monitoring.demo.enabled", havingValue="true")
public class DemoMonitoringConnector implements MonitoringConnector {
    @Override public String sourceName() { return "DEMO"; }

    @Override public CollectionBatch collect() {
        Instant end = Instant.now();
        Instant start = end.minusSeconds(1800);
        List<MetricObservation> metrics = new ArrayList<>();
        metrics.add(sample("GA4", "WEB", "active_users_last_30m", 520, "users", start, end));
        metrics.add(sample("GA4", "ANDROID", "active_users_last_30m", 1180, "users", start, end));
        metrics.add(sample("GA4", "IOS", "active_users_last_30m", 950, "users", start, end));
        metrics.add(sample("DYNATRACE", "BACKEND", "service_response_p95", 340, "ms", start, end));
        metrics.add(sample("DYNATRACE", "BACKEND", "service_response_p99", 810, "ms", start, end));
        metrics.add(sample("ELK", "BACKEND", "error_log_count_last_5m", 7, "logs", end.minusSeconds(300), end));
        return new CollectionBatch(sourceName(), metrics);
    }

    private MetricObservation sample(String source, String platform, String name, double value,
                                     String unit, Instant start, Instant end) {
        return new MetricObservation(source, platform, "ALL", name, value, unit, start, end, true);
    }
}
