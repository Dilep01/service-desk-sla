# Multi-Tenant IT Service Desk & SLA Management

A local Java full-stack service desk built with Java 21, Spring Boot, React/TypeScript and PostgreSQL. It runs without paid APIs or hosted services. The packaged backend serves the React interface from the same origin.

## Start with Docker

From this folder:

```powershell
docker compose up --build -d
```

- Application: http://localhost:8080
- Local email inbox: http://localhost:8025
- Stop: `docker compose stop`
- Inspect startup: `docker compose logs app`

The Compose setup uses PostgreSQL 18, Mailpit, and persistent database storage. It binds exposed ports to localhost. Do not add `-v` to a teardown command unless you intend to delete the database volume.

## Demo accounts

| Organization | Role | Email |
| --- | --- | --- |
| Northstar IT | Administrator | admin1@demo.local |
| Northstar IT | Support agent | agent1@demo.local |
| Northstar IT | Employee | requester1@demo.local |
| Harbor Services | Administrator | admin2@demo.local |
| Harbor Services | Support agent | agent2@demo.local |
| Harbor Services | Employee | requester2@demo.local |

All demo passwords are `Demo-local-2026!`. These users are seeded only with the `demo` profile. Invitations and password reset codes are delivered to the local Mailpit inbox, not to the public internet.

## Run natively on Windows

Requirements: JDK 21, Node.js, PostgreSQL. Docker is unnecessary for this route.

```powershell
.\scripts\run-local.ps1 -JavaHome 'C:\Program Files\Java\jdk-21.0.12' -PostgresBin 'C:\Program Files\PostgreSQL\17\bin'
```

The script creates a project-local database cluster in ignored `.tools/pgdata`, listens on 127.0.0.1:55439, downloads Maven with checksum verification if needed, builds the UI/JAR and runs the app. The local database uses trust authentication on loopback for development; do not reuse that configuration for a public server. Optional Mailpit is expected at `.tools/mailpit/mailpit.exe`, SMTP 11025 and inbox 18025. The container route supplies it automatically.

For separate frontend/backend development, run Maven `spring-boot:run` with the demo profile and appropriate database environment variables, then `npm ci` and `npm run dev` in frontend. Vite proxies HTTP and WebSockets to backend port 8080. Scripts invoke tool files directly because this project's folder name contains an ampersand, which breaks some Windows command wrappers.

## Included workflows

- Organizations, memberships, roles, invitations, password resets, login throttling and session revocation.
- Employee-owned tickets, support-team queues, eligible-agent assignment, team routing and version-conflict protection.
- Public replies, internal notes, customer-waiting states, resolution summaries, employee confirmation/reopening and preserved resolution-cycle history.
- Immutable SLA policy versions, business hours/time zones/holidays, pause/resume, configurable warnings and escalation recipients.
- PostgreSQL-backed notifications and transactional email outbox with retries; live WebSocket refresh and reconnect behavior.
- Knowledge articles, publication visibility, designated approver decisions, attachments with quotas and authorized download/deletion.
- Search/pagination, status/workload/SLA reports, audit history, team/member administration and email-delivery inspection.
- PostgreSQL row policies with verified transaction-local tenant context; a separate runtime-role setup script.
- Container packaging, CI configuration, database-backed tests, browser acceptance tests, backup/restore and a local load-check script.

## Tests

Backend tests requiring PostgreSQL are opt-in so a missing database is not disguised as a passing integration run:

```powershell
$env:RUN_DB_TESTS='true'
$env:DB_URL='jdbc:postgresql://127.0.0.1:55439/servicedesk_tests'
$env:DB_USER='servicedesk'
$env:DB_PASSWORD='local-development-only'
mvn -f backend/pom.xml test
```

Use a **separate test database** with permission to create the restricted test role. Alternatively run `scripts/test.ps1`. In frontend, `npm run build` checks TypeScript and produces the bundle. With the application on port 8080 and Mailpit on port 18025, run:

```powershell
node node_modules/@playwright/test/cli.js test
```

Browser tests use installed Chrome and create synthetic demo tickets/accounts. `.github/workflows/verify.yml` runs backend integration tests on PostgreSQL 18 and builds the UI when the project is pushed to GitHub. No repository has been published and no remote CI result is claimed.

## Backup and database roles

`scripts/backup.ps1` writes a custom-format database backup to ignored data/backups. Since attachments are stored in PostgreSQL, they are included. `scripts/restore-check.ps1 -Backup <file>` restores into a new uniquely named database; it never overwrites the live database.

`scripts/database-roles.sql` creates a non-owner runtime role after migrations. Configure DB_USER/DB_PASSWORD for that login and MIGRATION_USER/MIGRATION_PASSWORD separately for Flyway. Do not grant BYPASSRLS or table ownership to the runtime role. Local Compose defaults use the database owner for easy setup; the row-policy enforcement check used the restricted role.

## Design and evidence

- [Architecture and operating boundaries](docs/architecture.md)
- [API and permission guide](docs/api-guide.md)
- [Review walkthrough](docs/demo-script.md)
- [Implementation status and validation](docs/IMPLEMENTATION_STATUS.md)
- [Local load measurements](docs/validation/load-results.json)

The deliverable is a local portfolio application. Production hosting, high availability, external email reputation, SSO, full malware scanning, enterprise identity provisioning, and operational SLAs are outside this release. SMTP is at-least-once, so duplicates are possible after a crash. Multiple app instances need sticky routing because sessions are in memory. Calendars support one daytime interval per workday, not overnight or split shifts.

The files under docs/guides were created from the earlier 29 October implementation and are retained as historical learning material. For current feature and verification claims, use this README, the architecture/API guides, and IMPLEMENTATION_STATUS.md.

