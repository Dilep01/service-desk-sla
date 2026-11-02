# API and permission guide

All tenant routes begin `/api/tenants/{tenantId}`. Sessions authenticate requests; mutations need the token/header returned by `GET /api/csrf`. Login uses URL-encoded username/password at POST /api/login. Logout is POST /api/logout. IDs are UUIDs. Lists are bounded and no request body may override tenant authorization.

Ticket creation accepts an optional UUID `Idempotency-Key` header, used by the UI. A repeated key from the same tenant/user with identical input returns the original ticket; changing the payload with the same key returns 409. Advisory transaction locking serializes concurrent retries. Other creation endpoints do not offer this guarantee.

| Route suffix | Method | Permission |
| --- | --- | --- |
| tickets | GET | Own tickets for employees, team tickets for agents/leads, all for admin; page/search/status filters |
| tickets | POST | Any active member; team must belong to tenant |
| tickets/{id} | GET | Ticket visibility |
| tickets/{id}/comments | POST | Ticket visibility; only staff can create internal notes |
| tickets/{id}/transitions | POST | Visible ticket, staff, valid state and version; resolution requires summary |
| tickets/{id}/requester-action | POST | Ticket requester; confirm or reopen a resolved ticket |
| tickets/{id}/assignment | POST | Staff and visible ticket; assignee active in ticket team |
| tickets/{id}/priority | POST | Staff, open/in-progress, reason and version |
| teams | GET/POST | Members may list; admin creates |
| teams/{team}/members/{user} | PUT/DELETE | Admin enrollment/removal |
| teams/route/{ticket} | PUT | Admin; clears assignee and checks version |
| members/{user} | PUT | Admin; cannot remove last active admin |
| invitations | POST | Admin; local email with one-hour code |
| sla-policies | GET/POST | Admin; immutable versions |
| escalation | GET/PUT | Admin warning lead time and lead/admin escalation setting |
| reports, sla-report, audit | GET | Admin |
| notifications | GET | Current user's tenant notifications |
| notifications/{id}/read | POST | Notification recipient |
| articles | GET/POST | Members read; requesters see published only; admin writes |
| articles/{id} | PUT | Admin |
| tickets/{id}/approvals | GET/POST | Visible ticket; staff requests another eligible staff approver |
| tickets/{id}/approvals/{approval} | POST | Designated active staff approver, only once |
| tickets/{id}/attachments | GET/POST | Ticket visibility; size/quota/extension restrictions |
| tickets/{id}/attachments/{file} | GET/DELETE | Visible ticket; deletion requires uploader or admin |
| delivery | GET | Admin, delivery metadata only |
| delivery/{id}/retry | POST | Admin, undelivered message only |

Account endpoints: POST /api/organizations (authenticated), /api/account/accept, /api/account/reset-request, /api/account/reset, /api/account/revoke-sessions. Reset-request returns a generic success regardless of whether the email exists. Existing-account invitation acceptance requires signing in as that account and never changes its password.

WebSocket `/api/live?tenant={id}` requires the authenticated HTTP session, same-origin handshake and active membership. It sends only refresh markers. Clients cannot publish business events through it.

HTTP semantics: 400 invalid input, 401 unauthenticated/revoked, 403 disallowed operation/CSRF, 404 unavailable tenant or ticket, 409 stale version/conflict, 413 oversized file, 429 login/reset throttling.

Status workflow: OPEN → IN_PROGRESS → WAITING_FOR_CUSTOMER → IN_PROGRESS → RESOLVED → CLOSED. Staff may cancel active tickets. A public reply from the requester resumes a waiting ticket. A resolved ticket can reopen to IN_PROGRESS and archives the previous resolution outcome. CLOSED is terminal.
