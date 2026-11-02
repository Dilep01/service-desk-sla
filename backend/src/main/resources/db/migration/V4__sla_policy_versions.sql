CREATE TABLE sla_policy_version (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant,
 priority TEXT NOT NULL CHECK(priority IN ('P1','P2','P3','P4')),
 version INTEGER NOT NULL, definition TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,priority,version), UNIQUE(tenant_id,id)
);
ALTER TABLE ticket ADD COLUMN sla_policy_id UUID;
ALTER TABLE ticket ADD CONSTRAINT ticket_policy_fk FOREIGN KEY(tenant_id,sla_policy_id) REFERENCES sla_policy_version(tenant_id,id);
ALTER TABLE ticket ADD COLUMN paused_remaining_seconds BIGINT;
CREATE INDEX sla_policy_latest ON sla_policy_version(tenant_id,priority,version DESC);
