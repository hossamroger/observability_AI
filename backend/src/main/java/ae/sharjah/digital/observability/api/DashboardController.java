package ae.sharjah.digital.observability.api;

import ae.sharjah.digital.observability.domain.MetricObservation;
import ae.sharjah.digital.observability.persistence.MetricSnapshotRepository;
import ae.sharjah.digital.observability.service.LiveUpdatePublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class DashboardController {
    private final MetricSnapshotRepository snapshots;
    private final LiveUpdatePublisher updates;
    private final boolean demoMode;

    public DashboardController(MetricSnapshotRepository snapshots,
            LiveUpdatePublisher updates,
            @Value("${monitoring.demo.enabled:false}") boolean demoMode) {
        this.snapshots = snapshots;
        this.updates = updates;
        this.demoMode = demoMode;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        List<MetricObservation> metrics = snapshots.findLatestPerMetric(demoMode);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dataMode", demoMode ? "DEMO" : "LIVE");
        response.put("generatedAtUtc", Instant.now().toString());
        response.put("metrics", metrics);
        return response;
    }

    @GetMapping(value="/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() { return updates.subscribe(); }
}
