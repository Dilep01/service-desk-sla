# Implementation and validation status — 2 November 2026

The local portfolio feature set is implemented and the final native and container builds passed the checks below. This is not a production-readiness certification.

## Implemented

- Java 21/Spring Boot, React/TypeScript, PostgreSQL; a single packaged application serves the UI and API.
- Organizations, memberships, requester/agent/lead/admin permissions, team-scoped visibility and assignment, admin routing, membership deactivation and last-admin protection.
- Invitations and password reset with expiring single-use codes, CSRF-protected sessions, login/reset throttling and account session revocation.
- Incident and service-request tickets, atomic organization-specific ticket references, retry-safe ticket creation, search/pagination and optimistic concurrency.
- Public replies/internal notes, waiting/resume, required resolution summaries, requester confirmation/reopening and archived resolution cycles.
- Immutable SLA versions, business calendars, holidays/time zones, pauses, configurable warning/escalation behavior, audited priority changes and scheduler deduplication.
- Durable in-app notifications, read state, transactional email outbox, retries, administrator retry controls, local Mailpit delivery and authenticated WebSocket invalidation/reconnect.
- Knowledge drafts/publication/editing; one-time designated approvals; attachment quotas, authorized download/deletion and binary download headers.
- Status, workload and SLA outcome reports; tenant audit history.
- Database row policies, transaction-local tenant context, separate runtime-role setup, container packaging, CI configuration, backup/restore and local performance scripts.

## Verified evidence

- Final backend source: 42 tests passed, zero failures/errors/skips. This includes 9 unit tests and 33 PostgreSQL integration tests.
- Final frontend: TypeScript checking and Vite production build passed (36 modules).
- Final browser suite: all four acceptance tests passed on the native app (38.1 seconds) and on the PostgreSQL 18 Docker stack (40.9 seconds). Native execution used the restricted non-owner database role.
- Restricted-role SQL tests confirmed that an unscoped ticket query returns no rows without context and only the selected tenant's rows with context.
- Two live scheduler instances produced one breach event and two expected recipient notifications for one synthetic overdue ticket.
- Backup restored into a separate database with its attachment bytes. See validation/evidence.md for the snapshot and hash.
- Small local load check: 100 authenticated requests, concurrency 10, zero errors; measurements in validation/load-results.json.
- Final-source Docker build and startup succeeded with PostgreSQL 18 and Mailpit; all four browser acceptance tests passed against this stack.
- CI is configured but has not run on GitHub; no repository publication or public deployment was requested or performed.

## Deliberate release boundaries

- This is a local demo/portfolio application. No paid API, cloud subscription or real external email delivery is required.
- SMTP is at-least-once; crash recovery can produce duplicate emails. In-memory sessions require sticky routing across app instances.
- Calendars support one daytime interval per working day, not overnight or split shifts.
- Approval decisions are recorded; they do not provision real systems or gate every ticket transition.
- Attachments are extension/size/quota restricted and forced to download. There is no malware scanner or full file-content verification.
- Row security is enforced only for non-owner/non-superuser runtime roles. Compose's easy-start demo uses the database owner; follow database-roles.sql and migration/runtime credential separation to enforce it.
- No production penetration test, sustained capacity test, external security audit, high-availability certification or commercial service SLA is claimed.

## Review locations

- Native app: http://localhost:8080
- Native Mailpit: http://localhost:18025
- Isolated container verification uses ports 18090 (app), 18035 (mail inbox), 18039 (database); normal Compose uses 8080/8025/5432.
- README.md contains accounts and startup instructions; docs/demo-script.md is the review walkthrough.
- Existing docs/guides files are historical snapshots from before this implementation was completed. Current claims belong in this file, README.md and the architecture/API guides.


