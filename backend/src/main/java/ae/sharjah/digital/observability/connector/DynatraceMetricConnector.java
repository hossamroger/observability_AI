package ae.sharjah.digital.observability.connector;

import ae.sharjah.digital.observability.domain.CollectionBatch;
import ae.sharjah.digital.observability.domain.MetricObservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    private final String metricSelector;
    private final long entityCacheSeconds;
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
        this.metricSelector = additionalSelectors.trim().isEmpty() ? metricSelector
                : metricSelector + "," + additionalSelectors;
        this.entityCacheSeconds = Math.max(60, entityCacheSeconds);
    }
    @Override public String sourceName() { return "DYNATRACE"; }

    @Override public CollectionBatch collect() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Api-Token " + token);
            names.refresh(http, baseUrl, headers, entityCacheSeconds);
            String uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v2/metrics/query")
                    .queryParam("metricSelector", metricSelector)
                    .queryParam("from", "now-5m")
                    .queryParam("resolution", "1m")
                    .build().encode().toUriString();
            String body = http.exchange(uri, HttpMethod.GET,
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
                        } else if (metricKey.startsWith("builtin:service.errors.total.rate")) {
                            unit = "percent";
                        }
                        observations.add(new MetricObservation(sourceName(), "BACKEND",
                                names.resolve(entity), metricKey, value, unit,
                                when.minusSeconds(60), when, false));
                    }
                }
            }
            return new CollectionBatch(sourceName(), observations);
        } catch (Exception error) {
            throw new IllegalStateException("Dynatrace metrics collection failed: "
                    + error.getClass().getSimpleName(), error);
        }
    }
}
