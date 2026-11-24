package ae.sharjah.digital.observability.connector;

import ae.sharjah.digital.observability.domain.CollectionBatch;
import ae.sharjah.digital.observability.domain.MetricObservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Read-only GA4 intraday aggregates. No raw user or transaction identifiers leave BigQuery. */
@Component
@ConditionalOnExpression("${monitoring.bigquery.enabled:false} && !${monitoring.demo.enabled:false}")
public class BigQueryGa4Connector implements MonitoringConnector {
    private static final Logger LOG = LoggerFactory.getLogger(BigQueryGa4Connector.class);
    private final RestTemplate http;
    private final ObjectMapper json;
    private final String project, dataset, location, credentialsPath, web, android, ios;
    private final ZoneId zone;
    private final long interval, maximumBytes, lag;
    private GoogleCredentials credentials;

    public BigQueryGa4Connector(RestTemplate http, ObjectMapper json,
            @Value("${monitoring.bigquery.project-id:}") String project,
            @Value("${monitoring.bigquery.dataset-id:}") String dataset,
            @Value("${monitoring.bigquery.location:}") String location,
            @Value("${monitoring.bigquery.credentials-path:}") String credentialsPath,
            @Value("${monitoring.ga4.credentials-path:}") String ga4Credentials,
            @Value("${monitoring.ga4.streams.web:}") String web,
            @Value("${monitoring.ga4.streams.android:}") String android,
            @Value("${monitoring.ga4.streams.ios:}") String ios,
            @Value("${monitoring.bigquery.property-timezone:Asia/Dubai}") String timezone,
            @Value("${monitoring.bigquery.interval-ms:300000}") long interval,
            @Value("${monitoring.bigquery.maximum-bytes-billed:1073741824}") long maximumBytes,
            @Value("${monitoring.bigquery.lag-seconds:120}") long lag) {
        this.http = http; this.json = json;
        this.project = project; this.dataset = dataset; this.location = location;
        this.credentialsPath = credentialsPath.trim().isEmpty() ? ga4Credentials : credentialsPath;
        this.web = web; this.android = android; this.ios = ios;
        this.zone = ZoneId.of(timezone);
        this.interval = Math.max(300000L, interval);
        this.maximumBytes = maximumBytes; this.lag = Math.max(0L, lag);
    }

    @Override public String sourceName() { return "BIGQUERY"; }
    @Override public long minimumIntervalMillis() { return interval; }

    private void validate() {
        if (!project.matches("[a-z][a-z0-9-]{4,28}[a-z0-9]") || !dataset.matches("[A-Za-z0-9_]+")
                || !location.matches("[A-Za-z0-9-]+") || maximumBytes <= 0
                || !web.matches("[0-9]+") || !android.matches("[0-9]+") || !ios.matches("[0-9]+")
                || web.equals(android) || web.equals(ios) || android.equals(ios))
            throw new IllegalStateException("Invalid BigQuery configuration");
    }

    private GoogleCredentials credentials() throws Exception {
        if (credentials == null) {
            GoogleCredentials loaded;
            if (credentialsPath.trim().isEmpty()) loaded = GoogleCredentials.getApplicationDefault();
            else try (InputStream in = Files.newInputStream(Paths.get(credentialsPath.trim()))) {
                loaded = GoogleCredentials.fromStream(in);
            }
            credentials = loaded.createScoped(Collections.singleton("https://www.googleapis.com/auth/bigquery"));
        }
        credentials.refreshIfExpired();
        if (credentials.getAccessToken() == null) credentials.refresh();
        return credentials;
    }

    @Override public CollectionBatch collect() {
        try {
            validate();
            Instant end = Instant.now().minusSeconds(lag).truncatedTo(ChronoUnit.MINUTES);
            ZonedDateTime date = end.minusNanos(1).atZone(zone);
            Instant midnight = date.toLocalDate().atStartOfDay(zone).toInstant();
            Instant start = end.minusSeconds(1800);
            if (start.isBefore(midnight)) start = midnight;
            String table = project + "." + dataset + ".events_intraday_" + date.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
            ObjectNode request = json.createObjectNode();
            request.put("query", sql(table));
            request.put("useLegacySql", false);
            request.put("location", location);
            request.put("maximumBytesBilled", Long.toString(maximumBytes));
            request.put("timeoutMs", 10000);
            request.put("parameterMode", "NAMED");
            request.putArray("queryParameters")
                .add(param("web", "STRING", web)).add(param("android", "STRING", android))
                .add(param("ios", "STRING", ios))
                .add(param("start", "TIMESTAMP", start.toString()))
                .add(param("end", "TIMESTAMP", end.toString()));
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(credentials().getAccessToken().getTokenValue());
            headers.setContentType(MediaType.APPLICATION_JSON);
            String response = http.postForObject("https://bigquery.googleapis.com/bigquery/v2/projects/" + project + "/queries",
                    new HttpEntity<>(request, headers), String.class);
            JsonNode root = json.readTree(response);
            if (!root.path("jobComplete").asBoolean() || root.path("errors").size() > 0 || root.hasNonNull("pageToken"))
                throw new IllegalStateException("BigQuery result incomplete or failed");
            List<MetricObservation> observations = new ArrayList<>();
            for (JsonNode row : root.path("rows")) {
                JsonNode fields = row.path("f");
                String platform = fields.path(0).path("v").asText();
                String metric = fields.path(1).path("v").asText();
                if (!platform.matches("WEB|ANDROID|IOS") || !metric.matches("[a-z_]+")
                        || fields.path(2).path("v").isNull() || fields.path(2).path("v").isMissingNode())
                    throw new IllegalStateException("Unexpected BigQuery result");
                double value = Double.parseDouble(fields.path(2).path("v").asText());
                if (!Double.isFinite(value) || value < 0) throw new IllegalStateException("Invalid metric");
                observations.add(new MetricObservation(sourceName(), platform, "GA4_INTRADAY", metric,
                        metric.equals("observed_users") ? "users" : metric.equals("observed_sessions") ? "sessions" : "events",
                        start, end, false));
            }
            LOG.info("event=bigquery_ga4_aggregated observations={} bytes_processed={} window_start={} window_end={}",
                    observations.size(), root.path("totalBytesProcessed").asText("unknown"), start, end);
            return new CollectionBatch(sourceName(), observations);
        } catch (Exception failure) {
            throw new IllegalStateException("BigQuery GA4 collection failed", failure);
        }
    }

    private ObjectNode param(String name, String type, String value) {
        ObjectNode p = json.createObjectNode().put("name", name);
        p.putObject("parameterType").put("type", type);
        p.putObject("parameterValue").put("value", value);
        return p;
    }

    static String sql(String table) {
        return "WITH scoped AS (SELECT platform, event_name, user_pseudo_id, "
            + "(SELECT MAX(p.value.int_value) FROM UNNEST(event_params) p WHERE p.key='ga_session_id') session_id, "
            + "(SELECT MAX(LOWER(p.value.string_value)) FROM UNNEST(event_params) p WHERE p.key='action_result') action_result "
            + "FROM `" + table + "` WHERE event_timestamp >= UNIX_MICROS(@start) AND event_timestamp < UNIX_MICROS(@end) "
            + "AND ((platform='WEB' AND stream_id=@web) OR (platform='ANDROID' AND stream_id=@android) OR (platform='IOS' AND stream_id=@ios)) "
            + "AND NOT EXISTS (SELECT 1 FROM UNNEST(event_params) p WHERE p.key IN ('environment','environment_std') "
            + "AND p.value.string_value IS NOT NULL AND LOWER(TRIM(p.value.string_value)) NOT IN ('prd','production'))), "
            + "a AS (SELECT platform, COUNT(*) events, COUNT(DISTINCT user_pseudo_id) observed_users, "
            + "COUNT(DISTINCT IF(user_pseudo_id IS NOT NULL AND session_id IS NOT NULL, CONCAT(user_pseudo_id,':',CAST(session_id AS STRING)),NULL)) observed_sessions, "
            + "COUNTIF(event_name='session_start') session_starts, COUNTIF(event_name='screen_view') screen_views, "
            + "COUNTIF(event_name='page_view') page_views, COUNTIF(event_name='service_card_click') service_clicks, "
            + "COUNTIF(event_name='payment_initiated') payment_initiations, COUNTIF(event_name='purchase') purchase_events, "
            + "COUNTIF(event_name IN ('payment_failed','payment_fail')) payment_failure_events, "
            + "COUNTIF(event_name='application_action' AND action_result='success') workflow_success_events, "
            + "COUNTIF(event_name='application_action' AND action_result='failed') workflow_failure_events, "
            + "COUNTIF(event_name='application_initiate_action' AND action_result='success') workflow_initiation_success_events, "
            + "COUNTIF(event_name='application_initiate_action' AND action_result='failed') workflow_initiation_failure_events "
            + "FROM scoped GROUP BY platform) "
            + "SELECT platform, m.metric_name, m.metric_value FROM a, UNNEST(["
            + "STRUCT('observed_events' AS metric_name, events AS metric_value), STRUCT('observed_users',observed_users), "
            + "STRUCT('observed_sessions',observed_sessions), STRUCT('session_start_events',session_starts), "
            + "STRUCT('screen_view_events',screen_views), STRUCT('page_view_events',page_views), STRUCT('service_click_events',service_clicks), "
            + "STRUCT('payment_initiated_events',payment_initiations), STRUCT('purchase_events',purchase_events), "
            + "STRUCT('payment_failure_events',payment_failure_events), STRUCT('workflow_success_events',workflow_success_events), "
            + "STRUCT('workflow_failure_events',workflow_failure_events), STRUCT('workflow_initiation_success_events',workflow_initiation_success_events), "
            + "STRUCT('workflow_initiation_failure_events',workflow_initiation_failure_events)]) m "
            + "WHERE m.metric_name IN ('observed_events','observed_users','observed_sessions','session_start_events') "
            + "OR (platform IN ('ANDROID','IOS') AND m.metric_name IN ('screen_view_events','service_click_events','payment_initiated_events')) "
            + "OR (platform='IOS' AND m.metric_name IN ('purchase_events','payment_failure_events')) "
            + "OR (platform='WEB' AND (m.metric_name='page_view_events' OR STARTS_WITH(m.metric_name,'workflow_'))) ORDER BY platform,m.metric_name";
    }
}
