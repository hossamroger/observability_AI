package ae.sharjah.digital.observability.connector;

import ae.sharjah.digital.observability.domain.MetricObservation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Validate only mathematically necessary relationships within the same platform and scope. */
final class BigQueryAggregateValidator {
    private BigQueryAggregateValidator() { }

    static void validate(List<MetricObservation> observations) {
        Map<String, Map<String, Double>> scopes = new HashMap<>();
        for (MetricObservation observation : observations) {
            String scope = observation.platform + "|" + observation.serviceName;
            Map<String, Double> metrics = scopes.computeIfAbsent(scope, key -> new HashMap<>());
            double value = observation.metricValue;
            require(Double.isFinite(value) && value >= 0);
            if (!observation.metricName.endsWith("_ms")) require(value == Math.floor(value));
            require(metrics.put(observation.metricName, value) == null);
        }
        for (Map<String, Double> metrics : scopes.values()) {
            ordered(metrics, "observed_users", "observed_events");
            ordered(metrics, "observed_sessions", "observed_events");
            ordered(metrics, "session_start_events", "observed_events");
            ordered(metrics, "session_start_eligible_sessions", "session_start_events");
            ordered(metrics, "session_start_eligible_sessions", "observed_sessions");
            ordered(metrics, "quality_missing_session_key_events", "observed_events");
            ordered(metrics, "quality_missing_start_key_events", "session_start_events");
            ordered(metrics, "quality_missing_start_key_events", "quality_missing_session_key_events");
            ordered(metrics, "quality_missing_payment_key_events", "observed_events");
            ordered(metrics, "quality_unclassified_workflow_events", "observed_events");
            ordered(metrics, "workflow_total_events", "observed_events");
            ordered(metrics, "quality_unclassified_workflow_events", "workflow_total_events");
            if (metrics.containsKey("workflow_total_events")
                    && metrics.containsKey("workflow_success_events") && metrics.containsKey("workflow_failure_events")
                    && metrics.containsKey("workflow_initiation_success_events") && metrics.containsKey("workflow_initiation_failure_events")
                    && metrics.containsKey("quality_unclassified_workflow_events")) {
                double classified = metrics.get("workflow_success_events") + metrics.get("workflow_failure_events")
                        + metrics.get("workflow_initiation_success_events") + metrics.get("workflow_initiation_failure_events");
                require(classified + metrics.get("quality_unclassified_workflow_events") == metrics.get("workflow_total_events"));
            }
            ordered(metrics, "session_to_home_samples", "session_to_first_screen_samples");
            ordered(metrics, "session_to_first_screen_samples", "session_start_eligible_sessions");
            ordered(metrics, "payment_to_purchase_samples", "payment_initiated_events");
            ordered(metrics, "payment_to_purchase_samples", "purchase_events");
            for (String journey : new String[] {"session_to_first_screen", "session_to_home", "payment_to_purchase"}) {
                ordered(metrics, journey + "_p50_ms", journey + "_p95_ms");
                ordered(metrics, journey + "_p95_ms", journey + "_p99_ms");
                ordered(metrics, journey + "_over_60s", journey + "_over_30s");
                ordered(metrics, journey + "_over_30s", journey + "_over_10s");
                ordered(metrics, journey + "_over_10s", journey + "_over_5s");
                ordered(metrics, journey + "_over_5s", journey + "_samples");
            }
        }
    }

    private static void ordered(Map<String, Double> metrics, String lower, String upper) {
        if (metrics.containsKey(lower) && metrics.containsKey(upper)) require(metrics.get(lower) <= metrics.get(upper));
    }

    private static void require(boolean valid) {
        if (!valid) throw new BigQueryCollectionException(BigQueryCollectionException.Category.INVALID_RESULT);
    }
}
