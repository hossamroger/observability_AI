# Technical Plan — Digital Sharjah Unified Observability

**Scope agreed:** Web, Android and iOS panels; GA4 Data API; Dynatrace APM/RUM/crashes; ELK logs; Oracle 19c; Slack-based alerts; clean Java 8 code; local and WebLogic WAR deployment.

## 1. MVP demonstrator contained in this archive
- Java 8-targeted Spring Boot 2.7.18 WAR, modular connectors, collection loop, isolation of connector failures, structured audit entries, Oracle 19c DDL, H2 local profile.
- Source-specific GA4 realtime report, Dynatrace metric query, Elasticsearch error-count query implementations, disabled by default until configured.
- Angular interface with separate Web/Android/iOS/Backend screens, 30-second fallback refresh and SSE update events.
- Explicit `DEMO` label and fake sample data in local mode. No claims of live telemetry.
- Maven unit test source supplied, but dependencies/build cannot run in the current offline environment.

## 2. Next hardening milestone before production
- Implement authentication + role-based access + source credential storage + audit of administrative actions.
- Implement configurable service↔platform mapping (backend APIs can be shared across platforms).
- Implement Oracle-coordinated collection leases across clustered WebLogic nodes.
- Implement retry/backoff/rate-limit policies for each connector; source freshness thresholds.
- Extend GA4 reporting for historical metrics, event funnels, app version and event-name breakdowns.
- Implement Dynatrace response-time and percentile mapping for actual metric descriptors; crash/RUM and Problems adapters.
- Implement ELK index mapping rules per environment and targeted incident searches.
- Implement SLA/SLO definitions, window-based evaluation, multi-window burn-rate alerting, alert deduplication, Slack delivery and incident lifecycle.
- Add retention, table partitioning where licensed, archival jobs, idempotent collection keys/upsert, and rolling aggregation policies.
- Add readiness, integration and security tests on the target WebLogic installation.

## 3. Important semantics
- GA4 realtime values cover **a rolling last-30-minute window**, not fresh data since the previous 30-second poll. Repeated snapshots must never be summed for total users/events.
- P95/P99: take the source metric (or calculate from underlying individual samples), **never average percentiles**.
- Dynatrace / ELK correlations show a possible association unless shared trace IDs and request evidence establish attribution.
- All timestamps in the current Oracle schema are UTC by convention; configure JVM timezone UTC and test Oracle JDBC behavior.
- Do not copy raw ELK logs to Oracle. Log collection execution metadata, saved metric snapshots, changes and future alerts.
- Schema retention is not automated in the demonstrator. Establish a policy and implement purge/partitioning before production.
- SSO and production-grade secrets handling are not yet implemented.
- Spring Boot 2.7 standard open-source support ended in 2023; evaluate a commercial support route or approved migration plan before production.

## 4. Proposed acceptance tests
1. Local setup starts without external service credentials and displays explicitly labeled demo metrics.
2. Web / Android / iOS do not mix GA4 platform rows.
3. Failure in one connector does not prevent other connectors from polling.
4. Each collection attempt has source, status, count, duration, timestamp and sanitized error category in Oracle.
5. User interface updates without a manual browser refresh, even when the EventSource disconnects.
6. For live mode, source values have observation timestamps and are marked stale if delayed.
7. WAR deploys on the exact WebLogic domain; JNDI Oracle datasource works and no embedded Tomcat conflicts.
8. SLO alert duplicate, recovery and burn-rate tests pass before allowing Slack alerts in production.

## 5. Configuration decisions to close
- Exact WebLogic version/PSU and JNDI datasource naming.
- GA4 Property ID(s), stream mapping and service account read access.
- Dynatrace environment URL and agreed metric selectors / mobile crash scope.
- ELK cluster API endpoint, index patterns, timestamp and severity field names.
- Oracle retention, storage quota and audit compliance requirements.
- Slack integration account/channel and incident escalation policy.
