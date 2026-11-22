# WebLogic Deployment Checklist (NOT YET VERIFIED ON THE TARGET SERVER)

1. **Compatibility gate:** determine the exact WebLogic 12c maintenance release and PSU before deployment. This baseline targets **WebLogic 12.2.1.x** with Servlet 3.1, especially 12.2.1.4, and Java 8. WebLogic **12.1.3 supports Servlet 3.0**, so it is **not an approved target for this Spring Boot 2.7.18 WAR**. Do not deploy this WAR to 12.1.3 without revising the application stack.
   - If you have shell access to the WebLogic installation, run: `java -cp "$WL_HOME/server/lib/weblogic.jar" weblogic.version` (where `$WL_HOME` is the server installation's `wlserver` directory). Alternatively check installed product version and patches with your WebLogic administrator.
   - Also supply `java -version`, and the installed patch/PSU details, for environment certification.
   - Details: `COMPATIBILITY_MATRIX.md` next to this checklist.
2. Create Oracle 19c schema `DS_OBSERVABILITY` and run `database/oracle19c/001_initial_schema.sql` as schema owner.
3. Define a managed JDBC datasource and JNDI binding `jdbc/DSObservabilityDS`. Confirm app resolves `java:comp/env/jdbc/DSObservabilityDS` in your domain; adjust mapping if necessary.
4. Supply Java 8 runtime, trust stores, outbound network/TLS to GA4, Dynatrace, and Elasticsearch.
5. Build: `cd backend && mvn -DskipTests=false clean package` (Maven and Java 8 or JDK 9+ with release 8 configured).
6. Deploy `target/ds-observability.war` to the test domain; enable profile `weblogic` using supported JVM/environment settings.
7. Initially use only **one collector node** (`OBS_COLLECTOR_ENABLED=true` on that instance only); other nodes disabled. For WebLogic clusters, add DB lease and shared notification bus before horizontal collection scale.
8. Keep source connectors disabled until read-only credentials/configuration and field mappings are verified.
9. Require SSO/RBAC, network restriction, and secrets management **before production**; this starter does not implement authentication.
10. Validate health endpoint `/ds-observability/actuator/health` and API `/ds-observability/api/v1/overview`.
11. Angular frontend deploy separately; reverse-proxy `/api/` to the WAR path, or change frontend API base path.
12. Set `-Duser.timezone=UTC` consistently on collector JVM; migrate timestamp storage to a TZ-aware policy if domain standard differs.
13. Verify no credentials or PII appear in response payloads or logs. Test gateway error/retries and source quota handling.
