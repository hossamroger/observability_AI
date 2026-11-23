package ae.sharjah.digital.observability.connector;

import ae.sharjah.digital.observability.domain.CollectionBatch;
import ae.sharjah.digital.observability.domain.MetricObservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** GA4 Realtime API: aggregates for a rolling 30-minute window (not per-event raw logs). */
@Component
@ConditionalOnProperty(name="monitoring.ga4.enabled", havingValue="true")
public class Ga4RealtimeConnector implements MonitoringConnector {
    private static final String SCOPE = "https://www.googleapis.com/auth/analytics.readonly";
    private final RestTemplate http;
    private final ObjectMapper json;
    private final String propertyId;
    private final GoogleCredentials credentials;
    private final Map<String, String> productionStreams;

    public Ga4RealtimeConnector(RestTemplate http, ObjectMapper json,
            @Value("${monitoring.ga4.property-id}") String propertyId,
            @Value("${monitoring.ga4.credentials-path:}") String credentialsPath,
            @Value("${monitoring.ga4.streams.web:}") String webStream,
            @Value("${monitoring.ga4.streams.android:}") String androidStream,
            @Value("${monitoring.ga4.streams.ios:}") String iosStream) throws IOException {
        this.http = http;
        this.json = json;
        this.propertyId = propertyId;
        Map<String, String> streams = new LinkedHashMap<>();
        registerStream(streams, "WEB", webStream);
        registerStream(streams, "ANDROID", androidStream);
        registerStream(streams, "IOS", iosStream);
        this.productionStreams = Collections.unmodifiableMap(streams);
        this.credentials = loadCredentials(credentialsPath)
                .createScoped(Collections.singleton(SCOPE));
    }

    private static void registerStream(Map<String, String> streams, String platform, String streamId) {
        if (streamId == null || !streamId.trim().matches("[0-9]+")) {
            throw new IllegalArgumentException("GA4 stream ID must be configured for " + platform);
        }
        String id = streamId.trim();
        if (streams.containsValue(id)) {
            throw new IllegalArgumentException("GA4 stream IDs must be unique");
        }
        streams.put(platform, id);
    }

    private GoogleCredentials loadCredentials(String credentialsPath) throws IOException {
        if (credentialsPath == null || credentialsPath.trim().isEmpty()) {
            return GoogleCredentials.getApplicationDefault();
        }
        try (InputStream input = Files.newInputStream(Paths.get(credentialsPath.trim()))) {
            return ServiceAccountCredentials.fromStream(input);
        }
    }

    @Override public String sourceName() { return "GA4"; }

    @Override public CollectionBatch collect() {
        try {
            credentials.refreshIfExpired();
            if (credentials.getAccessToken() == null) credentials.refresh();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(credentials.getAccessToken().getTokenValue());
            headers.setContentType(MediaType.APPLICATION_JSON);
            com.fasterxml.jackson.databind.node.ObjectNode payload = json.createObjectNode();
            payload.set("dimensions", json.createArrayNode()
                    .add(json.createObjectNode().put("name", "platform"))
                    .add(json.createObjectNode().put("name", "streamId")));
            payload.set("metrics", json.createArrayNode()
                    .add(json.createObjectNode().put("name", "activeUsers"))
                    .add(json.createObjectNode().put("name", "eventCount")));
            com.fasterxml.jackson.databind.node.ArrayNode streamIds = json.createArrayNode();
            productionStreams.values().forEach(streamIds::add);
            payload.putObject("dimensionFilter").putObject("filter")
                    .put("fieldName", "streamId").putObject("inListFilter")
                    .set("values", streamIds);
            payload.put("limit", 1000);
            String request = json.writeValueAsString(payload);
            String endpoint = "https://analyticsdata.googleapis.com/v1beta/properties/"
                    + propertyId + ":runRealtimeReport";
            String body = http.exchange(endpoint, HttpMethod.POST,
                    new HttpEntity<>(request, headers), String.class).getBody();
            JsonNode root = json.readTree(body);
            List<MetricObservation> results = new ArrayList<>();
            Instant end = Instant.now();
            Instant start = end.minusSeconds(1800);
            for (JsonNode row : root.path("rows")) {
                String platform = row.path("dimensionValues").path(0).path("value")
                        .asText("UNKNOWN").toUpperCase(Locale.ROOT);
                String stream = row.path("dimensionValues").path(1).path("value").asText("UNKNOWN");
                if (!stream.equals(productionStreams.get(platform))) continue;
                String service = "STREAM_" + stream;
                double users = row.path("metricValues").path(0).path("value").asDouble();
                double events = row.path("metricValues").path(1).path("value").asDouble();
                results.add(new MetricObservation(sourceName(), platform, service,
                        "active_users_last_30m", users, "users", start, end, false));
                results.add(new MetricObservation(sourceName(), platform, service,
                        "event_count_last_30m", events, "events", start, end, false));
            }
            return new CollectionBatch(sourceName(), results);
        } catch (Exception error) {
            throw new IllegalStateException("GA4 realtime collection failed: "
                    + error.getClass().getSimpleName(), error);
        }
    }
}
