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
    private final ae.sharjah.digital.observability.persistence.CollectionExecutionLogRepository executionLogs;
    private final boolean demoMode;
    @org.springframework.beans.factory.annotation.Autowired
    private ae.sharjah.digital.observability.service.ApplicationRangeService applicationRanges;

    public DashboardController(MetricSnapshotRepository snapshots,
            LiveUpdatePublisher updates,
            ae.sharjah.digital.observability.persistence.CollectionExecutionLogRepository executionLogs,
            @Value("${monitoring.demo.enabled:false}") boolean demoMode) {
        this.snapshots = snapshots;
        this.updates = updates;
        this.executionLogs = executionLogs;
        this.demoMode = demoMode;
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ae.sharjah.digital.observability.connector.DynatraceMetricConnector dynatrace;

    @GetMapping("/service-window")
    public List<MetricObservation> serviceWindow(@org.springframework.web.bind.annotation.RequestParam(defaultValue="5") int minutes) {
        if (minutes != 5 && minutes != 15 && minutes != 30 && minutes != 60)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        if (demoMode || dynatrace == null) return java.util.Collections.emptyList();
        return dynatrace.collectWindow(minutes);
    }

    @GetMapping("/service-history")
    public List<MetricObservation> serviceHistory(
            @org.springframework.web.bind.annotation.RequestParam String serviceId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="60") int minutes) {
        if (!serviceId.matches("SERVICE-[A-Fa-f0-9]{16}") || (minutes != 60 && minutes != 360 && minutes != 1440))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        Instant end = Instant.now();
        return snapshots.findServiceHistory(serviceId, end.minusSeconds(minutes * 60L), end, demoMode);
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@org.springframework.web.bind.annotation.RequestParam(defaultValue="true") boolean includeServiceMetrics) {
        List<MetricObservation> metrics = snapshots.findLatestPerMetric(demoMode, includeServiceMetrics);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dataMode", demoMode ? "DEMO" : "LIVE");
        response.put("generatedAtUtc", Instant.now().toString());
        response.put("metrics", metrics);
        response.put("sourceOutcomes", demoMode ? java.util.Collections.emptyList() : executionLogs.findLatestSourceOutcomes());
        return response;
    }

    @org.springframework.web.bind.annotation.PostMapping("/application-range")
    public Map<String, Object> applicationRange(
            @org.springframework.web.bind.annotation.RequestParam(required=false) Integer minutes,
            @org.springframework.web.bind.annotation.RequestParam(required=false) String from,
            @org.springframework.web.bind.annotation.RequestParam(required=false) String to) {
        return applicationRanges.report(minutes, from, to);
    }

    @org.springframework.web.bind.annotation.PostMapping("/application-source-history")
    public Map<String, Object> applicationSourceHistory(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="360") int minutes) {
        return applicationRanges.history(minutes);
    }

    @GetMapping("/application-history")
    public List<MetricObservation> applicationHistory(
            @org.springframework.web.bind.annotation.RequestParam String platform,
            @org.springframework.web.bind.annotation.RequestParam String metric,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="360") int minutes) {
        if (!platform.matches("WEB|ANDROID|IOS") || !java.util.Arrays.asList(60, 360, 1440).contains(minutes)
                || !java.util.Arrays.asList("observed_sessions", "observed_events", "session_to_home_p95_ms",
                    "session_to_first_screen_p95_ms", "session_to_home_samples", "session_start_eligible_sessions",
                    "payment_initiated_events", "purchase_events", "payment_failure_events", "workflow_total_events").contains(metric))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        Instant end = Instant.now();
        return snapshots.findApplicationHistory(platform, metric, end.minusSeconds(minutes * 60L), end, demoMode);
    }

    @GetMapping(value="/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() { return updates.subscribe(); }
}
