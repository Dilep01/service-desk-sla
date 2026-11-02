ALTER TABLE tenant ADD COLUMN warning_minutes INTEGER NOT NULL DEFAULT 30 CHECK(warning_minutes BETWEEN 1 AND 1440);
ALTER TABLE tenant ADD COLUMN escalate_to_leads BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE ticket ADD COLUMN response_warned BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE ticket ADD COLUMN resolution_warned BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX active_response_deadline ON ticket(response_due) WHERE responded_at IS NULL AND NOT response_breached;
CREATE INDEX active_resolution_deadline ON ticket(resolution_due) WHERE resolved_at IS NULL AND NOT resolution_breached;
