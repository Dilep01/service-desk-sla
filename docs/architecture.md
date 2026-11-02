# Architecture and operating boundaries

The application is a single Spring Boot process serving the built React UI. PostgreSQL stores tenant data, uploaded bytes, SLA versions, resolution history, audit records, tokens and queued email. This is a local portfolio application, not a hosted production service.

Session cookies and CSRF protect mutations. Tenant URLs are selectors, not proof of access. The application checks active membership, then ticket ownership or support-team membership. Admin access is organization-scoped. Composite foreign keys prevent cross-tenant references.

PostgreSQL row policies add a second tenant boundary on business tables. The HTTP filter validates membership and establishes transaction-local desk.tenant before queries. Background SLA processing explicitly sets desk.worker within its transaction; live connections set their verified tenant. These settings protect against accidentally omitted tenant predicates, not arbitrary SQL execution by a compromised database client. Owners and superusers bypass row security. Use scripts/database-roles.sql and a separate runtime login, with MIGRATION_USER/MIGRATION_PASSWORD for Flyway, to enforce it in a deployment. The runtime role must not own tables or have BYPASSRLS. Identity/membership and outbound delivery tables are service-wide and remain application-authorized.

Account tokens use 32 random bytes, SHA-256 hashes in the token table, single-use consumption and one-hour expiry. The outbound email queue necessarily contains the delivery code until mail retention cleanup. Treat database backups and Mailpit as sensitive local development data. Existing account invitations require that account to be signed in and never replace its password. Password reset increments an account session version; HTTP requests and WebSockets recheck it.

Notifications are durable database records. An INSERT trigger queues email in the same transaction. Workers claim pending email rows using FOR UPDATE SKIP LOCKED, attempt SMTP, and retry up to five times. SMTP may accept a message before a process crashes; delivery is at least once and duplicate emails remain possible. Failed rows can be retried by an administrator. Email delivery is disabled unless DESK_MAIL_ENABLED=true. The local setup routes exclusively to Mailpit.

WebSockets carry generic invalidation signals, not ticket contents. Each connection is tied to an authenticated session and tenant membership, rechecked every three seconds. Same-origin handshake checks are retained. The client refetches through authorization-checked HTTP endpoints after reconnect. Audit-count polling supports multiple application processes without a broker, but is a simple local-scale design. In-memory login sessions require sticky routing in a multi-instance deployment.

SLA versions are immutable. Existing tickets retain their version. Resolution pauses preserve remaining business seconds. Reopening archives the earlier resolution outcome and starts a new resolution budget using the pinned policy; first response remains a ticket-lifetime measurement. Priority changes are explicit and audited, use the latest policy anchored to the original creation instant, and preserve prior breach flags. They are allowed only for open/in-progress tickets. Warnings occur a configurable number of elapsed minutes before the computed deadline. Escalations notify the assignee (or requester) and optionally active team leads/organization admins.

Calendars support one daytime interval, working weekdays and holidays per policy, with an IANA zone. Overnight shifts and multiple shifts per day are not supported. Stored instants use timestamptz. Tests use a fixed Java Clock for SLA transitions; the scheduler uses PostgreSQL time.

Attachments are limited to 2 MiB each and 50 MiB per tenant. They are served as downloads with nosniff, never as inline HTML. The extension allowlist is not a malware scanner. Database storage means a PostgreSQL backup includes attachment content.

Approvals are one-time decisions by a designated eligible colleague. They record a support decision; the system does not provision real accounts or execute external work.
