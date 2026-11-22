# Proposed System Architecture (MVP + planned phases)

```mermaid
flowchart TB
    GA[GA4 Realtime API] --> CG[GA4 Connector]
    DT[Dynatrace Environment API] --> CD[Dynatrace Connector]
    EL[Elasticsearch API] --> CE[ELK Connector]
    subgraph Java8[Java 8 / Spring Boot 2.7 WAR]
      CG --> COL[Collection Orchestrator]
      CD --> COL
      CE --> COL
      COL --> REP[Metrics & Collection Log Repositories]
      REP --> API[REST API and SSE Notifications]
    end
    REP --> OR[(Oracle 19c / H2 local)]
    API --> AN[Angular UI]
    OR -. planned .-> SLO[SLO and Correlation Engine]
    SLO -. planned .-> SL[Slack Alerts]
```

## Deploy variants

```mermaid
flowchart LR
  CODE[Single Source Base] --> BUILD[Executable WAR]
  BUILD --> LOCAL[Local Profile / Embedded Tomcat / H2 Demo]
  BUILD --> WLS[WebLogic Profile / External Servlet Container / Oracle 19c JNDI]
  UI[Angular build] --> STATIC[Separate static frontend / proxy to API]
```

## Code ownership
- `connector`: external API access and vendor-specific parsing.
- `domain`: normalized immutable-by-convention metric snapshots.
- `service`: per-source orchestration, failure isolation, structured collection logs, SSE.
- `persistence`: prepared SQL and Oracle/H2 storage.
- `api`: simple read-only dashboard API.
- `config`: environment-specific HTTP clients and application configuration.

## Clearly separated future modules
- `correlation`: match shared service, time and platform with explicit confidence.
- `slo`: SLI definitions, rolling evaluation, burn rate and error budget.
- `alerting`: deduplication, recovery, rate-limiting and Slack delivery.
- `security`: SSO, viewer/operator/admin permissions, auditing changes.

The architecture does **not** claim these future modules are already implemented. All source APIs need credentials, validation and testing in your network.
