CREATE TABLE support_team (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant,
 name VARCHAR(100) NOT NULL, UNIQUE(tenant_id,id), UNIQUE(tenant_id,name)
);
CREATE TABLE team_member (
 tenant_id UUID NOT NULL, team_id UUID NOT NULL, user_id UUID NOT NULL,
 PRIMARY KEY(tenant_id,team_id,user_id),
 FOREIGN KEY(tenant_id,team_id) REFERENCES support_team(tenant_id,id),
 FOREIGN KEY(tenant_id,user_id) REFERENCES membership(tenant_id,user_id)
);
ALTER TABLE ticket ADD COLUMN team_id UUID;
ALTER TABLE ticket ADD CONSTRAINT ticket_team_fk FOREIGN KEY(tenant_id,team_id) REFERENCES support_team(tenant_id,id);
ALTER TABLE ticket ADD CONSTRAINT ticket_status_check CHECK(status IN ('OPEN','IN_PROGRESS','WAITING_FOR_CUSTOMER','RESOLVED','CLOSED','CANCELLED'));
CREATE INDEX ticket_team_queue ON ticket(tenant_id,team_id,status);
