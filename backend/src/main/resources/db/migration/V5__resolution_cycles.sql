ALTER TABLE ticket ADD COLUMN resolution_summary TEXT;
ALTER TABLE ticket ADD COLUMN resolution_cycle INTEGER NOT NULL DEFAULT 1;
CREATE TABLE resolution_cycle_history (
 tenant_id UUID NOT NULL, ticket_id UUID NOT NULL, cycle INTEGER NOT NULL,
 deadline TIMESTAMPTZ NOT NULL, resolved_at TIMESTAMPTZ NOT NULL,
 breached BOOLEAN NOT NULL, summary TEXT,
 PRIMARY KEY(tenant_id,ticket_id,cycle),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES ticket(tenant_id,id)
);
