package ae.sharjah.digital.observability.connector;

/** GA4 event-timestamp proxies. All correlations stay within BigQuery and the observation window. */
final class BigQueryPerformanceSql {
    private BigQueryPerformanceSql() { }

    static String appendMetrics() {
        return ", session_events AS (SELECT *, MIN(IF(event_name='session_start',event_timestamp,NULL)) "
            + "OVER (PARTITION BY platform,user_pseudo_id,session_id) AS started_at FROM scoped "
            + "WHERE platform IN ('ANDROID','IOS') AND user_pseudo_id IS NOT NULL AND session_id IS NOT NULL "
            + "AND event_name IN ('session_start','screen_view')), "
            + "session_journeys AS (SELECT platform,user_pseudo_id,session_id,started_at, "
            + "IF(COUNT(DISTINCT IF(event_name='session_start' AND event_timestamp=started_at,app_version,NULL))=1, MAX(IF(event_name='session_start' AND event_timestamp=started_at,app_version,NULL)),NULL) AS session_version, "
            + "MIN(IF(event_name='screen_view' AND event_timestamp BETWEEN started_at AND started_at+300000000,event_timestamp,NULL)) AS first_screen_at, "
            + "MIN(IF(event_name='screen_view' AND screen_name=@home_screen AND event_timestamp BETWEEN started_at AND started_at+300000000,event_timestamp,NULL)) AS home_at "
            + "FROM session_events WHERE started_at IS NOT NULL AND started_at <= UNIX_MICROS(@end)-300000000 "
            + "GROUP BY platform,user_pseudo_id,session_id,started_at), "
            + "session_counts AS (SELECT platform,COUNT(*) AS eligible FROM session_journeys GROUP BY platform), "
            + "payment_attempts AS (SELECT user_pseudo_id,transaction_id, "
            + "MIN(IF(event_name='payment_initiated',event_timestamp,NULL)) AS initiated_at, "
            + "MIN(IF(event_name='purchase',event_timestamp,NULL)) AS purchased_at, "
            + "COUNT(DISTINCT IF(event_name='payment_initiated',event_timestamp,NULL)) AS initiation_times, "
            + "COUNT(DISTINCT IF(event_name='purchase',event_timestamp,NULL)) AS purchase_times "
            + "FROM scoped WHERE platform='IOS' AND user_pseudo_id IS NOT NULL AND transaction_id IS NOT NULL "
            + "AND event_name IN ('payment_initiated','purchase') GROUP BY user_pseudo_id,transaction_id), "
            + "duration_samples AS (SELECT platform,'session_to_first_screen' AS kind,(first_screen_at-started_at)/1000.0 AS duration_ms FROM session_journeys "
            + "UNION ALL SELECT platform,'session_to_home',(home_at-started_at)/1000.0 FROM session_journeys "
            + "UNION ALL SELECT 'IOS','payment_to_purchase',IF(initiation_times=1 AND purchase_times=1 "
            + "AND purchased_at>=initiated_at,(purchased_at-initiated_at)/1000.0,NULL) FROM payment_attempts), "
            + "duration_stats AS (SELECT platform,kind,COUNT(duration_ms) AS samples,AVG(duration_ms) AS average_ms, "
            + "APPROX_QUANTILES(duration_ms,100) AS quantiles, COUNTIF(duration_ms>5000) AS over5, COUNTIF(duration_ms>10000) AS over10, COUNTIF(duration_ms>30000) AS over30, COUNTIF(duration_ms>60000) AS over60 FROM duration_samples GROUP BY platform,kind), "
            + "performance_metrics AS (SELECT platform,CONCAT(kind,m.suffix) AS metric_name,m.metric_value "
            + "FROM duration_stats, UNNEST([STRUCT('_samples' AS suffix,CAST(samples AS FLOAT64) AS metric_value), "
            + "STRUCT('_average_ms',average_ms),STRUCT('_p50_ms',quantiles[SAFE_OFFSET(50)]), "
            + "STRUCT('_p95_ms',quantiles[SAFE_OFFSET(95)]),STRUCT('_p99_ms',quantiles[SAFE_OFFSET(99)]), STRUCT('_over_5s',CAST(over5 AS FLOAT64)), STRUCT('_over_10s',CAST(over10 AS FLOAT64)), STRUCT('_over_30s',CAST(over30 AS FLOAT64)), STRUCT('_over_60s',CAST(over60 AS FLOAT64))]) m "
            + "WHERE m.metric_value IS NOT NULL "
            + "UNION ALL SELECT platform,'session_start_eligible_sessions',CAST(eligible AS FLOAT64) FROM session_counts) "
            + ", version_samples AS (SELECT platform,IF(REGEXP_CONTAINS(session_version,r'^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$'),session_version,'UNKNOWN') AS version_name, "
            + "(first_screen_at-started_at)/1000.0 AS first_ms,(home_at-started_at)/1000.0 AS home_ms FROM session_journeys), "
            + "version_stats AS (SELECT platform,version_name,COUNT(*) AS eligible,COUNT(first_ms) AS first_count,COUNT(home_ms) AS home_count, "
            + "APPROX_QUANTILES(first_ms,100) AS first_quantiles,APPROX_QUANTILES(home_ms,100) AS home_quantiles FROM version_samples GROUP BY platform,version_name), "
            + "ranked_versions AS (SELECT *,ROW_NUMBER() OVER(PARTITION BY platform ORDER BY eligible DESC,version_name) AS version_rank FROM version_stats), "
            + "version_metrics AS (SELECT platform,m.metric_name,m.metric_value,CONCAT('APP_VERSION:',version_name) AS metric_scope FROM ranked_versions, "
            + "UNNEST([STRUCT('session_start_eligible_sessions' AS metric_name,CAST(eligible AS FLOAT64) AS metric_value), "
            + "STRUCT('session_to_first_screen_samples',CAST(first_count AS FLOAT64)),STRUCT('session_to_home_samples',CAST(home_count AS FLOAT64)), "
            + "STRUCT('session_to_first_screen_p50_ms',first_quantiles[SAFE_OFFSET(50)]),STRUCT('session_to_first_screen_p95_ms',first_quantiles[SAFE_OFFSET(95)]),STRUCT('session_to_first_screen_p99_ms',first_quantiles[SAFE_OFFSET(99)]), "
            + "STRUCT('session_to_home_p50_ms',home_quantiles[SAFE_OFFSET(50)]),STRUCT('session_to_home_p95_ms',home_quantiles[SAFE_OFFSET(95)]),STRUCT('session_to_home_p99_ms',home_quantiles[SAFE_OFFSET(99)])]) m "
            + "WHERE version_rank<=20 AND m.metric_value IS NOT NULL) "
            + "SELECT platform,metric_name,metric_value,'GA4_INTRADAY' AS metric_scope FROM base_metrics "
            + "UNION ALL SELECT platform,metric_name,metric_value,'GA4_INTRADAY' FROM performance_metrics "
            + "UNION ALL SELECT platform,metric_name,metric_value,metric_scope FROM version_metrics ORDER BY platform,metric_scope,metric_name";
    }

    static String metricUnit(String metric) {
        if (metric.endsWith("_ms")) return "ms";
        if ((metric.endsWith("_samples") || metric.matches(".*_over_(5|10|30|60)s"))) return "samples";
        if ("observed_users".equals(metric)) return "users";
        if ("observed_sessions".equals(metric) || metric.endsWith("_sessions")) return "sessions";
        return "events";
    }
}
