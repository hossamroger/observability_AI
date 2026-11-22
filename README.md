# Digital Sharjah Observability Platform

Unified observability dashboard for Google Analytics 4, Dynatrace, and ELK, with Oracle 19c storage and Slack alerts.

## Target architecture
- Backend: Java 8, Spring Boot 2.7.18, WAR deployment on Oracle WebLogic 12c (exact version compatibility to be verified)
- Local development: executable WAR with embedded Tomcat
- Frontend: Angular
- Database: Oracle 19c, separate observability schema
- Sources: GA4 (Web, Android, iOS), Dynatrace, ELK
- Design: modular connectors, descriptive structured logs, configurable SLOs and alert rules

## Status
Repository initialized. The starter source archive prepared in ChatGPT has **not yet been imported** into this repository. No deployment or integration tests have been run. Do not treat this README as a completed application.

## Security
Never commit API tokens, credentials, or production secrets. Use environment-specific secret management.
