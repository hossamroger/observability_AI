package ae.sharjah.digital.observability.connector;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** One query over the requested export interval. Each bucket has independent raw event aggregates. */
final class BigQueryHistorySql {
    static final Set<String> METRICS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "observed_sessions", "observed_events", "session_to_home_p95_ms", "session_to_first_screen_p95_ms",
            "payment_initiated_events", "purchase_events", "payment_failure_events", "workflow_total_events")));
    private BigQueryHistorySql() { }

    static String appendMetrics() {
        return BigQueryPerformanceSql.sessionJourneys()
            + ", activity AS (SELECT platform,DIV(event_timestamp-UNIX_MICROS(@start),@bucket_us) AS bucket_index, "
            + "COUNT(*) AS events, COUNT(DISTINCT IF(user_pseudo_id IS NOT NULL AND session_id IS NOT NULL, "
            + "CONCAT(user_pseudo_id,':',CAST(session_id AS STRING)),NULL)) AS sessions, "
            + "COUNTIF(event_name='payment_initiated') AS initiated, COUNTIF(event_name='purchase') AS purchased, "
            + "COUNTIF(event_name IN ('payment_failed','payment_fail')) AS failed, "
            + "COUNTIF(event_name IN ('application_action','application_initiate_action')) AS workflows "
            + "FROM scoped GROUP BY platform,bucket_index), "
            + "activity_metrics AS (SELECT platform,m.metric_name,CAST(m.metric_value AS FLOAT64) AS metric_value,bucket_index "
            + "FROM activity,UNNEST([STRUCT('observed_events' AS metric_name,events AS metric_value), "
            + "STRUCT('observed_sessions',sessions),STRUCT('payment_initiated_events',initiated),STRUCT('purchase_events',purchased), "
            + "STRUCT('payment_failure_events',failed),STRUCT('workflow_total_events',workflows)]) m "
            + "WHERE m.metric_name IN ('observed_events','observed_sessions') "
            + "OR (platform IN ('ANDROID','IOS') AND m.metric_name='payment_initiated_events') "
            + "OR (platform='IOS' AND m.metric_name IN ('purchase_events','payment_failure_events')) "
            + "OR (platform='WEB' AND m.metric_name='workflow_total_events')), "
            + "startup AS (SELECT platform,DIV(started_at-UNIX_MICROS(@start),@bucket_us) AS bucket_index, "
            + "APPROX_QUANTILES((first_screen_at-started_at)/1000.0,100)[SAFE_OFFSET(95)] AS first_p95, "
            + "APPROX_QUANTILES((home_at-started_at)/1000.0,100)[SAFE_OFFSET(95)] AS home_p95 "
            + "FROM session_journeys GROUP BY platform,bucket_index), "
            + "startup_metrics AS (SELECT platform,m.metric_name,m.metric_value,bucket_index FROM startup, "
            + "UNNEST([STRUCT('session_to_first_screen_p95_ms' AS metric_name,first_p95 AS metric_value), "
            + "STRUCT('session_to_home_p95_ms',home_p95)]) m WHERE m.metric_value IS NOT NULL) "
            + "SELECT platform,metric_name,metric_value,bucket_index FROM activity_metrics "
            + "UNION ALL SELECT platform,metric_name,metric_value,bucket_index FROM startup_metrics ORDER BY bucket_index,platform,metric_name";
    }
}
