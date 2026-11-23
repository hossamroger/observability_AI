package ae.sharjah.digital.observability.connector;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

final class DynatraceServiceNameResolver {
    private static final Logger LOG = LoggerFactory.getLogger(DynatraceServiceNameResolver.class);
    private final Map<String, String> names = new HashMap<>();
    private Instant nextRefresh = Instant.EPOCH;

    synchronized void refresh(RestTemplate http, String baseUrl, HttpHeaders headers, long ttlSeconds) {
        if (Instant.now().isBefore(nextRefresh)) return;
        try {
            Map<String, String> updated = new HashMap<>();
            String cursor = null;
            do {
                org.springframework.web.util.UriComponentsBuilder uri =
                        org.springframework.web.util.UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v2/entities");
                if (cursor == null) uri.queryParam("entitySelector", "type(SERVICE)").queryParam("pageSize", 500);
                else uri.queryParam("nextPageKey", cursor);
                JsonNode response = http.exchange(uri.build().encode().toUri(), HttpMethod.GET,
                        new HttpEntity<Void>(headers), JsonNode.class).getBody();
                if (response == null) throw new IllegalStateException("Empty entity response");
                for (JsonNode entity : response.path("entities")) {
                    String id = entity.path("entityId").asText();
                    String name = entity.path("displayName").asText();
                    if (!id.isEmpty() && !name.isEmpty()) updated.put(id, name);
                }
                cursor = response.path("nextPageKey").asText("");
            } while (!cursor.isEmpty());
            names.clear();
            names.putAll(updated);
            nextRefresh = Instant.now().plusSeconds(ttlSeconds);
            LOG.info("event=dynatrace_service_cache_refreshed service_count={}", names.size());
        } catch (Exception error) {
            nextRefresh = Instant.now().plusSeconds(60);
            LOG.warn("event=dynatrace_service_cache_refresh_failed error_type={} fallback=entity_id", error.getClass().getSimpleName());
        }
    }

    synchronized String resolve(String id) {
        return names.containsKey(id) ? names.get(id) + " [" + id + "]" : id;
    }
}
