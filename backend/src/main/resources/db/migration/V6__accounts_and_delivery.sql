ALTER TABLE app_user ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE account_token (
 hash TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('INVITE','RESET')),
 tenant_id UUID REFERENCES tenant, email TEXT NOT NULL, role TEXT,
 expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ
);
CREATE TABLE mail_outbox (
 id UUID PRIMARY KEY, tenant_id UUID REFERENCES tenant, recipient TEXT NOT NULL,
 subject TEXT NOT NULL, body TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 available_at TIMESTAMPTZ NOT NULL DEFAULT now(), delivered_at TIMESTAMPTZ, last_error TEXT
);
CREATE INDEX mail_pending ON mail_outbox(available_at) WHERE delivered_at IS NULL;
CREATE TABLE login_bucket (address TEXT PRIMARY KEY, attempts INTEGER NOT NULL, expires_at TIMESTAMPTZ NOT NULL);
CREATE TABLE notification_delivery (
 notification_id UUID PRIMARY KEY REFERENCES notification(id), delivered_at TIMESTAMPTZ
);
CREATE OR REPLACE FUNCTION enqueue_notification_mail() RETURNS trigger AS $$
BEGIN
 INSERT INTO mail_outbox(id,tenant_id,recipient,subject,body)
 SELECT NEW.id,NEW.tenant_id,email,'Service Desk notification',NEW.message FROM app_user WHERE id=NEW.user_id;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER notification_mail AFTER INSERT ON notification FOR EACH ROW EXECUTE FUNCTION enqueue_notification_mail();
