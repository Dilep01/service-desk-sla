CREATE TABLE app_user (
 id UUID PRIMARY KEY, email TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, password_hash TEXT NOT NULL
);
CREATE TABLE tenant (id UUID PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE membership (
 tenant_id UUID NOT NULL REFERENCES tenant, user_id UUID NOT NULL REFERENCES app_user,
 role TEXT NOT NULL CHECK (role IN ('REQUESTER','AGENT','LEAD','ADMIN')),
 active BOOLEAN NOT NULL DEFAULT TRUE, PRIMARY KEY(tenant_id,user_id)
);
CREATE TABLE ticket (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant,
 title VARCHAR(200) NOT NULL, description TEXT NOT NULL,
 requester_id UUID NOT NULL, assignee_id UUID,
 priority TEXT NOT NULL CHECK(priority IN ('P1','P2','P3','P4')),
 status TEXT NOT NULL DEFAULT 'OPEN', version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 response_due TIMESTAMPTZ NOT NULL, resolution_due TIMESTAMPTZ NOT NULL,
 responded_at TIMESTAMPTZ, resolved_at TIMESTAMPTZ, paused_at TIMESTAMPTZ,
 response_breached BOOLEAN NOT NULL DEFAULT FALSE, resolution_breached BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,requester_id) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,assignee_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX ticket_queue ON ticket(tenant_id,status,created_at DESC);
CREATE TABLE comment (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, ticket_id UUID NOT NULL,
 author_id UUID NOT NULL, body TEXT NOT NULL, internal BOOLEAN NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES ticket(tenant_id,id),
 FOREIGN KEY(tenant_id,author_id) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE audit_event (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant, ticket_id UUID,
 actor_id UUID REFERENCES app_user, action TEXT NOT NULL, details TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES ticket(tenant_id,id)
);
CREATE TABLE notification (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, user_id UUID NOT NULL,
 message TEXT NOT NULL, read_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,user_id) REFERENCES membership(tenant_id,user_id)
);
