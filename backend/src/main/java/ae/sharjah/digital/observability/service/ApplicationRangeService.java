package ae.sharjah.digital.observability.service;

import ae.sharjah.digital.observability.connector.BigQueryGa4Connector;
import ae.sharjah.digital.observability.domain.CollectionBatch;
import ae.sharjah.digital.observability.domain.MetricObservation;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Explicit, bounded reporting requests. Never persist these as scheduled rolling snapshots. */
@Service
public class ApplicationRangeService {
    private final ObjectProvider<BigQueryGa4Connector> connectors;
    private final boolean enabled;
    private final long cooldownNanos;
    private final Map<String, CachedRange> cache = new LinkedHashMap<>(16, 0.75f, true);
    private boolean running;
    private long lastStarted;
    private boolean attempted;

    public ApplicationRangeService(ObjectProvider<BigQueryGa4Connector> connectors,
            @Value("${monitoring.bigquery.on-demand-enabled:false}") boolean enabled,
            @Value("${monitoring.bigquery.on-demand-cooldown-seconds:60}") long cooldownSeconds) {
        this.connectors = connectors;
        this.enabled = enabled;
        this.cooldownNanos = TimeUnit.SECONDS.toNanos(Math.max(60, cooldownSeconds));
    }

    public Map<String, Object> report(Integer minutes, String from, String to) {
        BigQueryGa4Connector connector = connectors.getIfAvailable();
        if (!enabled || connector == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "On-demand analytics disabled");
        Instant latest = connector.latestAvailableEnd();
        Instant[] window = resolveWindow(minutes, from, to, latest);
        String key = window[0] + "/" + window[1];
        synchronized (this) {
            CachedRange existing = cache.get(key);
            if (existing != null && System.nanoTime() - existing.createdNanos < TimeUnit.MINUTES.toNanos(5)) return existing.response;
            if (running || (attempted && System.nanoTime() - lastStarted < cooldownNanos))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Analytics request in progress or cooling down");
            running = true;
            attempted = true;
            lastStarted = System.nanoTime();
        }
        try {
            CollectionBatch batch = connector.collectWindow(window[0], window[1]);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("windowStartUtc", window[0].toString());
            response.put("windowEndUtc", window[1].toString());
            response.put("generatedAtUtc", Instant.now().toString());
            response.put("partial", batch.partial);
            response.put("metrics", batch.observations);
            synchronized (this) {
                cache.put(key, new CachedRange(response));
                while (cache.size() > 10) cache.remove(cache.keySet().iterator().next());
            }
            return response;
        } catch (RuntimeException failure) {
            // Vendor exception bodies are deliberately not returned to callers.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "BigQuery reporting request failed");
        } finally {
            synchronized (this) { running = false; }
        }
    }

    static Instant[] resolveWindow(Integer minutes, String from, String to, Instant latest) {
        try {
            Instant start, end;
            if (minutes != null && from == null && to == null) {
                if (!Arrays.asList(5, 15, 30, 60, 360, 1440, 10080).contains(minutes)) throw new IllegalArgumentException();
                end = Instant.ofEpochSecond(Math.floorDiv(latest.getEpochSecond(), 300) * 300);
                start = end.minusSeconds(minutes * 60L);
            } else if (minutes == null && from != null && to != null) {
                start = Instant.parse(from);
                end = Instant.parse(to);
            } else throw new IllegalArgumentException();
            Duration length = Duration.between(start, end);
            if (length.compareTo(Duration.ofMinutes(5)) < 0 || length.compareTo(Duration.ofDays(7)) > 0 || end.isAfter(latest))
                throw new IllegalArgumentException();
            return new Instant[] {start, end};
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid analytics window");
        }
    }

    private static final class CachedRange {
        final long createdNanos = System.nanoTime();
        final Map<String, Object> response;
        CachedRange(Map<String, Object> response) { this.response = response; }
    }
}
