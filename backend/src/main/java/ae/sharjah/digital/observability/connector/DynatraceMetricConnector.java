package ae.sharjah.digital.observability.connector;

import ae.sharjah.digital.observability.domain.CollectionBatch;
import ae.sharjah.digital.observability.domain.MetricObservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Metric selectors are configured per environment; never assume one P99 metric fits all services. */
@Component
@ConditionalOnProperty(name="monitoring.dynatrace.enabled", havingValue="true")
public class DynatraceMetricConnector implements MonitoringConnector {
    private final RestTemplate http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String token;
    private static final Logger LOG = LoggerFactory.getLogger(DynatraceMetricConnector.class);
    private final Set<String> metricSelectors = new LinkedHashSet<>();
    private final long entityCacheSeconds;
    @Value("${monitoring.dynatrace.management-zone-id:}")
    private String managementZoneId;
    private final DynatraceServiceNameResolver names = new DynatraceServiceNameResolver();

    public DynatraceMetricConnector(RestTemplate http, ObjectMapper json,
            @Value("${monitoring.dynatrace.url}") String baseUrl,
            @Value("${monitoring.dynatrace.api-token}") String token,
            @Value("${monitoring.dynatrace.metric-selector}") String metricSelector,
            @Value("${monitoring.dynatrace.additional-metric-selectors:}") String additionalSelectors,
            @Value("${monitoring.dynatrace.entity-cache-seconds:900}") long entityCacheSeconds) {
        this.http = http;
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        addSelectors(metricSelector);
        addSelectors(additionalSelectors);
        this.entityCacheSeconds = Math.max(60, entityCacheSeconds);
    }
    private void addSelectors(String input) {
        // Split only top-level commas, preserving commas inside selector transformations.
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '~' && i + 1 < input.length()) { i++; continue; }
            if (ch == '"') quoted = !quoted;
            if (!quoted) {
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                else if (ch == ',' && depth == 0) {
                    addSelector(input.substring(start, i));
                    start = i + 1;
                }
            }
        }
        addSelector(input.substring(start));
    }

    private void addSelector(String selector) {
        if (!selector.trim().isEmpty()) metricSelectors.add(selector.trim());
    }

    @Override public String sourceName() { return "DYNATRACE"; }

    @Override public CollectionBatch collect() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Api-Token " + token);
        names.refresh(http, baseUrl, headers, entityCacheSeconds);
        List<MetricObservation> observations = new ArrayList<>();
        int successful = 0;
        Exception lastError = null;
        for (String selector : metricSelectors) {
            try {
                List<MetricObservation> batch = collectSelector(selector, headers);
                observations.addAll(batch);
                successful++;
                LOG.info("event=dynatrace_metric_collection_success selector={} count={}", selector, batch.size());
            } catch (Exception error) {
                lastError = error;
                String category = error instanceof HttpStatusCodeException
                        ? "HTTP_" + ((HttpStatusCodeException) error).getRawStatusCode()
                        : error.getClass().getSimpleName();
                LOG.warn("event=dynatrace_metric_collection_failed selector={} category={}", selector, category);
            }
        }
        if (successful == 0) throw new IllegalStateException("All Dynatrace metric queries failed", lastError);
        if (successful < metricSelectors.size()) {
            LOG.warn("event=dynatrace_partial_collection successful={} total={}", successful, metricSelectors.size());
        }
        return new CollectionBatch(sourceName(), observations);
    }

    private final java.util.Map<Integer, List<MetricObservation>> windowCache = new java.util.HashMap<>();
    private final java.util.Map<Integer, Instant> cacheEnds = new java.util.HashMap<>();

    public synchronized List<MetricObservation> collectWindow(int minutes) {
        if (minutes != 5 && minutes != 15 && minutes != 30 && minutes != 60)
            throw new IllegalArgumentException("Unsupported reporting window");
        Instant end = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).minusSeconds(60);
        if (end.equals(cacheEnds.get(minutes))) return windowCache.get(minutes);
        Instant start = end.minusSeconds(minutes * 60L);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Api-Token " + token);
        names.refresh(http, baseUrl, headers, entityCacheSeconds);
        List<MetricObservation> result = new ArrayList<>();
        int successes = 0;
        Set<String> reportingSelectors = new LinkedHashSet<>(metricSelectors);
        reportingSelectors.add("builtin:service.errors.total.count:value");
        reportingSelectors.add("builtin:service.errors.total.successCount:value");
        for (String selector : reportingSelectors) {
            try {
                result.addAll(collectSelector(selector, headers, start, end));
                successes++;
            } catch (Exception error) {
                LOG.warn("event=dynatrace_window_query_failed selector={} minutes={} category={}",
                        selector, minutes, error instanceof HttpStatusCodeException
                        ? "HTTP_" + ((HttpStatusCodeException) error).getRawStatusCode()
                        : error.getClass().getSimpleName());
            }
        }
        if (successes == 0) throw new IllegalStateException("All reporting window queries failed");
        LOG.info("event=dynatrace_window_collection minutes={} start={} end={} successful={} total={} count={}",
                minutes, start, end, successes, reportingSelectors.size(), result.size());
        List<MetricObservation> immutable = java.util.Collections.unmodifiableList(result);
        windowCache.put(minutes, immutable);
        cacheEnds.put(minutes, end);
        return immutable;
    }

    private List<MetricObservation> collectSelector(String selector, HttpHeaders headers) throws Exception {
        return collectSelector(selector, headers, null, null);
    }

    private List<MetricObservation> collectSelector(String selector, HttpHeaders headers, Instant start, Instant end) throws Exception {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v2/metrics/query")
                    .queryParam("metricSelector", selector)
                    .queryParam("from", start == null ? "now-5m" : start.toEpochMilli())
                    .queryParam("to", end == null ? "now" : end.toEpochMilli())
                    .queryParam("resolution", start == null ? "1m" : "Inf");
            if (start != null && managementZoneId != null && !managementZoneId.trim().isEmpty()) {
                builder.queryParam("mzSelector", "mzId(" + managementZoneId.trim() + ")");
            }
            String uri = builder.build().encode().toUriString();
            String body = http.exchange(java.net.URI.create(uri), HttpMethod.GET,
                    new HttpEntity<Void>(headers), String.class).getBody();
            JsonNode root = json.readTree(body);
            List<MetricObservation> observations = new ArrayList<>();
            for (JsonNode metric : root.path("result")) {
                String metricKey = metric.path("metricId").asText("unknown_metric");
                for (JsonNode data : metric.path("data")) {
                    String entity = data.path("dimensions").size() > 0
                            ? data.path("dimensions").get(0).asText() : "ALL";
                    JsonNode timestamps = data.path("timestamps");
                    JsonNode values = data.path("values");
                    if (start != null && values.size() != 1) throw new IllegalStateException("Expected one aggregate per service");
                    for (int index = 0; index < values.size() && index < timestamps.size(); index++) {
                        if (values.get(index).isNull()) continue;
                        Instant when = Instant.ofEpochMilli(timestamps.get(index).asLong());
                        double value = values.get(index).asDouble();
                        String unit = "source-defined";
                        if (metricKey.startsWith("builtin:service.response.time:") || metricKey.equals("builtin:service.response.time")) {
                            value /= 1000.0;
                            unit = "ms";
                        } else if (metricKey.startsWith("builtin:service.requestCount")) {
                            unit = "requests";
                        } else if (metricKey.startsWith("builtin:service.errors.total.count") || metricKey.startsWith("builtin:service.errors.total.successCount")) {
                            unit = "requests";
                        } else if (metricKey.startsWith("builtin:service.errors.total.rate")) {
                            unit = "percent";
                        }
                        observations.add(new MetricObservation(sourceName(), "BACKEND",
                                names.resolve(entity), metricKey, value, unit,
                                start == null ? when.minusSeconds(60) : start, end == null ? when : end, false));
                    }
                }
            }
            return observations;
    }
}
