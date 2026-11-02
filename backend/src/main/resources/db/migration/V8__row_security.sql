DO $$
DECLARE table_name TEXT;
BEGIN
 FOREACH table_name IN ARRAY ARRAY['ticket','comment','audit_event','notification','support_team','team_member','knowledge_article','ticket_approval','ticket_attachment','sla_policy_version','resolution_cycle_history'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING (tenant_id::text = current_setting(''desk.tenant'',true) OR current_setting(''desk.worker'',true) = ''on'') WITH CHECK (tenant_id::text = current_setting(''desk.tenant'',true) OR current_setting(''desk.worker'',true) = ''on'')',table_name);
 END LOOP;
END $$;
