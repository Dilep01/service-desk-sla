-- Run once as a database administrator after Flyway migrations.
-- Application and migration connections should use different credentials.
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='desk_runtime') THEN
  CREATE ROLE desk_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
 END IF;
END $$;
GRANT USAGE ON SCHEMA public TO desk_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO desk_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO desk_runtime;
REVOKE ALL ON flyway_schema_history FROM desk_runtime;
GRANT SELECT ON flyway_schema_history TO desk_runtime;
-- Assign LOGIN and a password separately for deployment. Never make this role
-- a table owner or grant BYPASSRLS. Set spring.flyway.user/password independently.
