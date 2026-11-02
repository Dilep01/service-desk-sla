# Local validation evidence

## Final release checks — 2 November 2026

- Backend: 42 tests passed, zero failures/errors/skips (9 unit and 33 PostgreSQL integration).
- Frontend: TypeScript and production build passed, 36 modules.
- Final native browser run: 4 passed in 38.1 seconds.
- Final Docker build/start succeeded with PostgreSQL 18 and Mailpit; browser run: 4 passed in 40.9 seconds.

## Native application

- PostgreSQL 17.4 on localhost:55439, separate servicedesk_tests database for integration tests.
- Browser checks passed against two Spring Boot instances sharing the main database, using desk_runtime (non-owner, no BYPASSRLS) and a separate Flyway migration connection.
- The browser suite exercised ticket creation, public/internal replies, attachment upload, resolution/reopening, live invalidation, tenant rejection, invite/reset email delivery to Mailpit, session revocation, policy administration and a 390 px mobile viewport.
- A synthetic overdue ticket was processed while both application schedulers were active: one SLA_BREACHED audit event and two expected recipient notifications were observed. This is an idempotence check, not high-availability certification.

## Recovery

- Custom-format backup created at data/backups/20261102_103737.dump.
- SHA-256: E7A0407265E56ECBE90A3C1BEFE1C8CE0D15E79EA021F5F6965E7F06BB1C3C1C.
- Restored into new database servicedesk_restore_2fe1a0a3f3 without overwriting live data.
- Final restored snapshot contained four tickets, three attachments totaling 36 bytes, and successful migrations V1–V10.

## Performance

See load-results.json for the actual local run: 100 authenticated queue requests at concurrency 10, zero errors, p95 42 ms. Dataset and hardware are small/local; this is not a throughput or latency guarantee for production.

## Scope limits

No public deployment, paid service, external email, or remote GitHub CI run was performed. Mailpit captures messages locally. Business calendars have one daytime interval. SMTP is at-least-once. Sessions are in memory, requiring sticky routing across instances. File uploads are not malware-scanned. The authorization suite and row policies do not substitute for an independent security review.

