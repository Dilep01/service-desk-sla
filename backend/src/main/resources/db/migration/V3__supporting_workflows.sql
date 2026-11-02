CREATE TABLE knowledge_article (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant,
 title VARCHAR(200) NOT NULL, body TEXT NOT NULL, published BOOLEAN NOT NULL DEFAULT false,
 author_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,author_id) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE ticket_approval (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, ticket_id UUID NOT NULL,
 approver_id UUID NOT NULL, requested_by UUID NOT NULL,
 reason TEXT NOT NULL, decision TEXT CHECK(decision IN ('APPROVED','REJECTED')),
 decided_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES ticket(tenant_id,id),
 FOREIGN KEY(tenant_id,approver_id) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,requested_by) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE ticket_attachment (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, ticket_id UUID NOT NULL,
 uploader_id UUID NOT NULL, name VARCHAR(200) NOT NULL, mime_type TEXT NOT NULL,
 content BYTEA NOT NULL CHECK(octet_length(content)<=2097152),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES ticket(tenant_id,id),
 FOREIGN KEY(tenant_id,uploader_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX article_tenant ON knowledge_article(tenant_id,published);
CREATE INDEX approval_ticket ON ticket_approval(tenant_id,ticket_id);
CREATE INDEX attachment_ticket ON ticket_attachment(tenant_id,ticket_id);
