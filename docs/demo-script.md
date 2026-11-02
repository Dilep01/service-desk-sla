# Review walkthrough

Use separate browser profiles or incognito windows for employee and agent sessions.

1. Sign in as requester1@demo.local. Create a P3 ticket, select IT Support and describe a VPN issue.
2. Sign in as agent1@demo.local in another browser. The ticket should appear without a manual refresh. Open it, assign it to agent 1 and start work.
3. Add an internal note, then a public reply. Confirm that the employee sees only the public reply.
4. Upload a small text attachment as the employee; download it from the authorized ticket.
5. Move the ticket to Waiting for Customer. Reply as the employee and verify work resumes.
6. Enter a resolution summary and resolve it. Reopen as the employee; inspect the archived cycle and new deadline. Resolve again and confirm closure.
7. Sign in as admin1@demo.local. Create a team, enroll an agent, route a ticket, and inspect workload and audit history.
8. Save a business-hour SLA version. Create a new ticket of that priority and compare its deadline with the policy. Existing tickets retain their prior version.
9. Invite a synthetic email address. Open Mailpit, copy its code and accept the invitation. Request a password reset and confirm that the old session loses access.
10. Create a knowledge draft and a published article. The requester sees only published content.
11. Request approval from another eligible colleague, approve once, and verify a second decision is refused.
12. Sign in as admin2@demo.local. Northstar tickets must not appear or be retrievable using their tenant/ticket URL.

Automated evidence is in docs/IMPLEMENTATION_STATUS.md. Files under frontend/test-results are generated browser screenshots/traces and are ignored by Git. The load script uses a small synthetic dataset. Do not present those results as enterprise capacity or real-world service-desk performance.
