CREATE TABLE ticket_request (
 tenant_id UUID NOT NULL, user_id UUID NOT NULL, request_key UUID NOT NULL,
 fingerprint TEXT NOT NULL, ticket_id UUID NOT NULL,
 PRIMARY KEY(tenant_id,user_id,request_key),
 FOREIGN KEY(tenant_id,user_id) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES ticket(tenant_id,id)
);
ALTER TABLE ticket_request ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON ticket_request USING(tenant_id::text=current_setting('desk.tenant',true)) WITH CHECK(tenant_id::text=current_setting('desk.tenant',true));
