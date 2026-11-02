ALTER TABLE tenant ADD COLUMN next_ticket_number BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ticket ADD COLUMN ticket_number BIGINT;
ALTER TABLE ticket ADD COLUMN ticket_type TEXT NOT NULL DEFAULT 'INCIDENT' CHECK(ticket_type IN ('INCIDENT','REQUEST'));
WITH numbered AS (SELECT id,row_number() OVER(PARTITION BY tenant_id ORDER BY created_at,id) AS n FROM ticket)
UPDATE ticket SET ticket_number=numbered.n FROM numbered WHERE ticket.id=numbered.id;
UPDATE tenant SET next_ticket_number=COALESCE((SELECT max(ticket_number)+1 FROM ticket WHERE ticket.tenant_id=tenant.id),1);
CREATE FUNCTION assign_ticket_number() RETURNS trigger AS $$
BEGIN
 UPDATE tenant SET next_ticket_number=next_ticket_number+1 WHERE id=NEW.tenant_id RETURNING next_ticket_number-1 INTO NEW.ticket_number;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER ticket_number_before_insert BEFORE INSERT ON ticket FOR EACH ROW EXECUTE FUNCTION assign_ticket_number();
ALTER TABLE ticket ALTER COLUMN ticket_number SET NOT NULL;
ALTER TABLE ticket ADD CONSTRAINT ticket_number_unique UNIQUE(tenant_id,ticket_number);
