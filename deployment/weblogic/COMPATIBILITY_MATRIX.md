# WebLogic 12c Compatibility Matrix — Digital Sharjah Observability

This document deliberately distinguishes WebLogic **12c product family** from an exact product release.

| WebLogic release | Java 8 | Servlet specification | Spring Boot 2.7.18 Java 8 WAR |
|---|---|---|---|
| 12.1.3 | Java 8 availability depends on product stack/certification | 3.0 | **Not supported by this baseline** (Boot requires Servlet 3.1+) |
| 12.2.1.3 | Certified configurations need verification | 3.1 | **Candidate**; test and check current patch/certification |
| 12.2.1.4 | Java 8 is certified (subject to supported update level) | 3.1 | **Preferred candidate**; test before production |

**Why this matters:** Spring Boot 2.7.18 supports external containers implementing Servlet 3.1 or 4.0. A plain answer of 'WebLogic 12c' does not uniquely establish Servlet capability.

## Exact version commands

From the managed server host where WebLogic is installed:

```bash
# $WL_HOME should point to a WebLogic Server wlserver installation.
java -cp "$WL_HOME/server/lib/weblogic.jar" weblogic.version
java -version
```

If you cannot run the command, request the precise release and installed PSU from the WebLogic administrator. Confirm JNDI, security provider behavior, driver compatibility, and WebLogic classloading on your target server.

## Configurations included in this starter

- `backend/pom.xml`: Java 8, Spring Boot 2.7.18, executable WAR with provided embedded Tomcat for local running.
- `backend/src/main/java/.../ObservabilityApplication.java`: direct `WebApplicationInitializer` implementation for external WebLogic.
- `backend/src/main/webapp/WEB-INF/weblogic.xml`: context root and application-preferred logging dependencies.
- `backend/src/main/resources/application-local.yml`: H2 local demo configuration.
- `backend/src/main/resources/application-weblogic.yml`: read-only external-source connectors and Oracle JNDI datasource config.
- `deployment/weblogic/DEPLOYMENT.md`: deployment checklist.

**Unverified:** This starter is source code; no Maven build or WebLogic deployment has been run in the authoring environment. This does not represent a deployable or production-hardened WAR yet.

## Vendor references

- Spring Boot 2.7.18 system requirements: https://docs.spring.io/spring-boot/docs/2.7.18/reference/html/getting-started.html
- Spring Boot 2.7.18 external WAR for WebLogic: https://docs.spring.io/spring-boot/docs/2.7.18/reference/html/howto.html#howto.traditional-deployment.weblogic
- Oracle WebLogic 12.1.3 specifications: https://docs.oracle.com/middleware/1213/wls/NOTES/whatsnew.htm
- Oracle WebLogic 12.2.1.4 specifications: https://docs.oracle.com/en/middleware/fusion-middleware/weblogic-server/12.2.1.4/notes/whatsnew.html
