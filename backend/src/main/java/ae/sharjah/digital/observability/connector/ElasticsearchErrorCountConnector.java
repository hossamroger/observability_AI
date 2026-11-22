package ae.sharjah.digital.observability.connector;

import ae.sharjah.digital.observability.domain.CollectionBatch;
import ae.sharjah.digital.observability.domain.MetricObservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Default error field is configurable because ELK index mappings are environment-specific. */
@Component
@ConditionalOnProperty(name="monitoring.elk.enabled", havingValue="true")
public class ElasticsearchErrorCountConnector implements MonitoringConnector {
    private final RestTemplate http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String indexPattern;
    private final String apiKey;
    private final String levelField;
    private final String errorValue;

    public ElasticsearchErrorCountConnector(RestTemplate http, ObjectMapper json,
            @Value("${monitoring.elk.url}") String baseUrl,
            @Value("${monitoring.elk.index-pattern}") String indexPattern,
            @Value("${monitoring.elk.api-key}") String apiKey,
            @Value("${monitoring.elk.level-field:log.level}") String levelField,
            @Value("${monitoring.elk.error-value:ERROR}") String errorValue) {
        this.http = http;
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.indexPattern = indexPattern;
        this.apiKey = apiKey;
        this.levelField = levelField;
        this.errorValue = errorValue;
    }
    @Override public String sourceName() { return "ELK"; }

    @Override public CollectionBatch collect() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "ApiKey " + apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            String query = json.writeValueAsString(Collections.singletonMap("query",
                    Collections.singletonMap("bool", Collections.singletonMap("filter",
                            new Object[] {
                              Collections.singletonMap("range", Collections.singletonMap("@timestamp",
                                      Collections.singletonMap("gte", "now-5m"))),
                              Collections.singletonMap("term", Collections.singletonMap(levelField, errorValue))
                            }))));
            String body = http.exchange(baseUrl + "/" + indexPattern + "/_count",
                    HttpMethod.POST, new HttpEntity<>(query, headers), String.class).getBody();
            JsonNode result = json.readTree(body);
            Instant end = Instant.now();
            return new CollectionBatch(sourceName(), Collections.singletonList(
                    new MetricObservation(sourceName(), "BACKEND", "ALL",
                            "error_log_count_last_5m", result.path("count").asDouble(),
                            "logs", end.minusSeconds(300), end, false)));
        } catch (Exception error) {
            throw new IllegalStateException("ELK error count collection failed: "
                    + error.getClass().getSimpleName(), error);
        }
    }
}
