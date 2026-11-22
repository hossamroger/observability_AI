package ae.sharjah.digital.observability.domain;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** An observed aggregate, not necessarily an individual raw event. */
public class MetricObservation {
    public final String id;
    public final String sourceSystem;
    public final String platform;
    public final String serviceName;
    public final String metricName;
    public final double metricValue;
    public final String metricUnit;
    public final Instant windowStartUtc;
    public final Instant windowEndUtc;
    public final Instant collectedAtUtc;
    public final boolean demo;

    public MetricObservation(String sourceSystem, String platform, String serviceName,
                             String metricName, double metricValue, String metricUnit,
                             Instant windowStartUtc, Instant windowEndUtc, boolean demo) {
        this(stableId(sourceSystem, platform, serviceName, metricName, windowStartUtc, windowEndUtc, demo),
                sourceSystem, platform, serviceName, metricName,
                metricValue, metricUnit, windowStartUtc, windowEndUtc, Instant.now(), demo);
    }

    public MetricObservation(String id, String sourceSystem, String platform, String serviceName,
                             String metricName, double metricValue, String metricUnit,
                             Instant windowStartUtc, Instant windowEndUtc,
                             Instant collectedAtUtc, boolean demo) {
        this.id = id;
        this.sourceSystem = sourceSystem;
        this.platform = platform;
        this.serviceName = serviceName;
        this.metricName = metricName;
        this.metricValue = metricValue;
        this.metricUnit = metricUnit;
        this.windowStartUtc = windowStartUtc;
        this.windowEndUtc = windowEndUtc;
        this.collectedAtUtc = collectedAtUtc;
        this.demo = demo;
    }

    /** Stable ID avoids duplicate imports from overlapping 5-minute source queries. */
    private static String stableId(String source, String platform, String service, String name,
                                   Instant start, Instant end, boolean demo) {
        String key = source + "|" + platform + "|" + service + "|" + name
                + "|" + start.toString() + "|" + end.toString() + "|" + demo;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
