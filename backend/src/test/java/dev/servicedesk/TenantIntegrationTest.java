package dev.servicedesk;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class TenantIntegrationTest {
  @Test
  void ticketReferencesAreUniqueWithinOrganization() throws Exception {
    seed();
    mvc.perform(
            post("/api/tenants/" + tenant + "/tickets")
                .with(user(requester + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"title\":\"New software\",\"description\":\"Please install"
                        + " editor\",\"priority\":\"P4\",\"ticketType\":\"REQUEST\"}"))
        .andExpect(status().isOk());
    assertEquals(
        2,
        db.queryForObject(
            "SELECT count(DISTINCT ticket_number) FROM ticket WHERE tenant_id=?",
            Integer.class,
            tenant));
    assertEquals(
        "REQUEST",
        db.queryForObject(
            "SELECT ticket_type FROM ticket WHERE tenant_id=? AND title='New software'",
            String.class,
            tenant));
  }

  @Test
  void duplicateTicketSubmissionReusesResult() throws Exception {
    seed();
    UUID key = UUID.randomUUID();
    String body =
        "{\"title\":\"Retry-safe request\",\"description\":\"Details\",\"priority\":\"P3\"}";
    String endpoint = "/api/tenants/" + tenant + "/tickets";
    String first =
        mvc.perform(
                post(endpoint)
                    .with(user(requester + "@test.local"))
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            post(endpoint)
                .with(user(requester + "@test.local"))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(content().json(first));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM ticket WHERE tenant_id=? AND title='Retry-safe request'",
            Integer.class,
            tenant));
    mvc.perform(
            post(endpoint)
                .with(user(requester + "@test.local"))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body.replace("Details", "Changed")))
        .andExpect(status().isConflict());
  }

  @Test
  void customerReplyResumesPausedTicket() throws Exception {
    seed();
    db.update(
        "UPDATE ticket SET"
            + " status='WAITING_FOR_CUSTOMER',paused_at=now(),paused_remaining_seconds=3600 WHERE"
            + " id=?",
        ticket);
    mvc.perform(
            post(path() + "/comments")
                .with(user(requester + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"body\":\"Here are the requested details\",\"internal\":false}"))
        .andExpect(status().isOk());
    assertEquals(
        "IN_PROGRESS",
        db.queryForObject("SELECT status FROM ticket WHERE id=?", String.class, ticket));
    assertNull(
        db.queryForObject(
            "SELECT paused_at FROM ticket WHERE id=?", java.sql.Timestamp.class, ticket));
  }

  @Test
  void completedCyclesAndActiveWorkAreSeparate() throws Exception {
    seed();
    db.update("UPDATE membership SET role='ADMIN' WHERE tenant_id=? AND user_id=?", tenant, agent);
    db.update(
        "UPDATE ticket SET responded_at=now(),response_due=now()+interval '1 hour' WHERE id=?",
        ticket);
    mvc.perform(get("/api/tenants/" + tenant + "/sla-report").with(user(agent + "@test.local")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.response.completed").value(1))
        .andExpect(jsonPath("$.response.within_target").value(1))
        .andExpect(jsonPath("$.resolution.completed").value(0))
        .andExpect(jsonPath("$.active.unassigned").value(1));
  }

  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  UUID tenant = UUID.randomUUID(),
      outsider = UUID.randomUUID(),
      agent = UUID.randomUUID(),
      requester = UUID.randomUUID(),
      team = UUID.randomUUID(),
      otherTeam = UUID.randomUUID(),
      ticket = UUID.randomUUID();

  void seed() {
    db.update(
        "INSERT INTO tenant(id,name) VALUES(?,?),(?,?)",
        tenant,
        "Test tenant",
        outsider,
        "Other tenant");
    db.update(
        "INSERT INTO app_user(id,email,display_name,password_hash) VALUES(?,?,?,?),(?,?,?,?)",
        agent,
        agent + "@test.local",
        "Agent",
        "unused",
        requester,
        requester + "@test.local",
        "Requester",
        "unused");
    db.update(
        "INSERT INTO membership(tenant_id,user_id,role) VALUES(?,?,'AGENT'),(?,?,'REQUESTER')",
        tenant,
        agent,
        tenant,
        requester);
    db.update(
        "INSERT INTO support_team(id,tenant_id,name) VALUES(?,?,'Support'),(?,?,'Private')",
        team,
        tenant,
        otherTeam,
        tenant);
    db.update(
        "INSERT INTO team_member(tenant_id,team_id,user_id) VALUES(?,?,?)", tenant, team, agent);
    db.update(
        "INSERT INTO"
            + " ticket(id,tenant_id,title,description,requester_id,priority,response_due,resolution_due,team_id)"
            + " VALUES(?,?,'Test','Details',?,'P3',now()+interval '4 hours',now()+interval '24"
            + " hours',?)",
        ticket,
        tenant,
        requester,
        team);
  }

  String path() {
    return "/api/tenants/" + tenant + "/tickets/" + ticket;
  }

  @Test
  void crossTenantReadIsHidden() throws Exception {
    seed();
    mvc.perform(
            get("/api/tenants/" + outsider + "/tickets/" + ticket)
                .with(user(agent + "@test.local")))
        .andExpect(status().isNotFound());
  }

  @Test
  void staffCannotReadOtherTeam() throws Exception {
    seed();
    db.update("UPDATE ticket SET team_id=? WHERE id=?", otherTeam, ticket);
    mvc.perform(get(path()).with(user(agent + "@test.local"))).andExpect(status().isNotFound());
    mvc.perform(get("/api/tenants/" + tenant + "/tickets").with(user(agent + "@test.local")))
        .andExpect(content().json("[]"));
  }

  @Test
  void internalNotesAreNotReturnedToRequester() throws Exception {
    seed();
    db.update(
        "INSERT INTO comment(id,tenant_id,ticket_id,author_id,body,internal)"
            + " VALUES(?,?,?,?,?,true)",
        UUID.randomUUID(),
        tenant,
        ticket,
        agent,
        "Private investigation");
    mvc.perform(get(path()).with(user(requester + "@test.local")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comments").isEmpty());
  }

  @Test
  void csrfAndRequesterPermissionsEnforced() throws Exception {
    seed();
    mvc.perform(
            post(path() + "/comments")
                .with(user(requester + "@test.local"))
                .contentType("application/json")
                .content("{\"body\":\"Hi\",\"internal\":false}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path() + "/comments")
                .with(user(requester + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"body\":\"Hi\",\"internal\":true}"))
        .andExpect(status().isForbidden());
    assertEquals(
        0,
        db.queryForObject("SELECT count(*) FROM comment WHERE ticket_id=?", Integer.class, ticket));
  }

  @Test
  void staleTransitionDoesNotOverwrite() throws Exception {
    seed();
    mvc.perform(
            post(path() + "/transitions")
                .with(user(agent + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"status\":\"IN_PROGRESS\",\"version\":9}"))
        .andExpect(status().isConflict());
    assertEquals(
        "OPEN", db.queryForObject("SELECT status FROM ticket WHERE id=?", String.class, ticket));
  }

  @Test
  void inactiveMembershipLosesAccess() throws Exception {
    seed();
    db.update("UPDATE membership SET active=false WHERE tenant_id=? AND user_id=?", tenant, agent);
    mvc.perform(get(path()).with(user(agent + "@test.local"))).andExpect(status().isNotFound());
  }

  @Test
  void agentCannotCreateTeams() throws Exception {
    seed();
    mvc.perform(
            post("/api/tenants/" + tenant + "/teams")
                .with(user(agent + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"Unauthorized\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminCanRouteAndClearsAssignment() throws Exception {
    seed();
    db.update("UPDATE membership SET role='ADMIN' WHERE tenant_id=? AND user_id=?", tenant, agent);
    db.update("UPDATE ticket SET assignee_id=? WHERE id=?", agent, ticket);
    mvc.perform(
            put("/api/tenants/" + tenant + "/teams/route/" + ticket)
                .with(user(agent + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"teamId\":\"" + otherTeam + "\",\"version\":0}"))
        .andExpect(status().isOk());
    assertNull(db.queryForObject("SELECT assignee_id FROM ticket WHERE id=?", UUID.class, ticket));
    assertEquals(
        otherTeam, db.queryForObject("SELECT team_id FROM ticket WHERE id=?", UUID.class, ticket));
  }

  @Test
  void assignmentRejectsStaffOutsideTeam() throws Exception {
    seed();
    db.update(
        "UPDATE membership SET role='ADMIN' WHERE tenant_id=? AND user_id=?", tenant, requester);
    mvc.perform(
            post(path() + "/assignment")
                .with(user(agent + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"assignee\":\"" + requester + "\",\"version\":0}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void draftArticlesHiddenFromEmployees() throws Exception {
    seed();
    db.update(
        "INSERT INTO knowledge_article(id,tenant_id,title,body,author_id)"
            + " VALUES(?,?,'Draft','Private draft',?)",
        UUID.randomUUID(),
        tenant,
        agent);
    mvc.perform(get("/api/tenants/" + tenant + "/articles").with(user(requester + "@test.local")))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void downloadRequiresTicketVisibility() throws Exception {
    seed();
    UUID file = UUID.randomUUID();
    db.update(
        "INSERT INTO ticket_attachment(id,tenant_id,ticket_id,uploader_id,name,mime_type,content)"
            + " VALUES(?,?,?,?,?,?,?)",
        file,
        tenant,
        ticket,
        agent,
        "test.txt",
        "application/octet-stream",
        "hello".getBytes());
    db.update("UPDATE ticket SET team_id=? WHERE id=?", otherTeam, ticket);
    mvc.perform(get(path() + "/attachments/" + file).with(user(agent + "@test.local")))
        .andExpect(status().isNotFound());
    mvc.perform(get(path() + "/attachments/" + file).with(user(requester + "@test.local")))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(content().bytes("hello".getBytes()));
  }

  @Test
  void onlyDesignatedApproverCanDecideOnce() throws Exception {
    seed();
    UUID approval = UUID.randomUUID();
    db.update(
        "INSERT INTO ticket_approval(id,tenant_id,ticket_id,approver_id,requested_by,reason)"
            + " VALUES(?,?,?,?,?,?)",
        approval,
        tenant,
        ticket,
        agent,
        requester,
        "Access approval");
    mvc.perform(
            post(path() + "/approvals/" + approval)
                .with(user(requester + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"decision\":\"APPROVED\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path() + "/approvals/" + approval)
                .with(user(agent + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"decision\":\"APPROVED\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            post(path() + "/approvals/" + approval)
                .with(user(agent + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"decision\":\"REJECTED\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void attachmentRejectsUnsafeNames() throws Exception {
    seed();
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "file", "../secret.txt", "text/plain", "test".getBytes());
    mvc.perform(
            multipart(path() + "/attachments")
                .file(file)
                .with(user(requester + "@test.local"))
                .with(csrf()))
        .andExpect(status().isBadRequest());
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM ticket_attachment WHERE ticket_id=?", Integer.class, ticket));
  }

  @Test
  void policyVersionsPreserveExistingDeadline() throws Exception {
    seed();
    db.update("UPDATE membership SET role='ADMIN' WHERE tenant_id=? AND user_id=?", tenant, agent);
    String endpoint = "/api/tenants/" + tenant + "/sla-policies";
    String policy =
        "{\"priority\":\"P3\",\"responseMinutes\":120,\"resolutionMinutes\":480,\"businessHours\":true,\"zone\":\"Europe/London\",\"opens\":\"09:00\",\"closes\":\"17:00\",\"days\":[1,2,3,4,5],\"holidays\":[]}";
    mvc.perform(
            post(endpoint)
                .with(user(agent + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content(policy))
        .andExpect(status().isOk());
    var clock =
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-03-27T16:30:00Z"), java.time.ZoneOffset.UTC);
    var service = new SlaPolicies(db, json, clock);
    var original = service.targets(tenant, "P3", clock.instant());
    assertEquals(java.time.Instant.parse("2026-03-30T09:30:00Z"), original.response());
    db.update(
        "UPDATE ticket SET sla_policy_id=?,response_due=?,resolution_due=? WHERE id=?",
        original.policyId(),
        java.sql.Timestamp.from(original.response()),
        java.sql.Timestamp.from(original.resolution()),
        ticket);
    mvc.perform(
            post(endpoint)
                .with(user(agent + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content(policy.replace("120", "240")))
        .andExpect(status().isOk());
    assertEquals(
        original.response(),
        db.queryForObject(
                "SELECT response_due FROM ticket WHERE id=?", java.sql.Timestamp.class, ticket)
            .toInstant());
    assertNotEquals(original.policyId(), service.targets(tenant, "P3", clock.instant()).policyId());
  }

  @Test
  void pauseRetainsBusinessTimeAcrossWeekend() {
    seed();
    var start = java.time.Instant.parse("2026-03-27T16:00:00Z");
    var service = new SlaPolicies(db, json, java.time.Clock.fixed(start, java.time.ZoneOffset.UTC));
    var definition =
        new SlaPolicies.Definition(
            "P3",
            60,
            120,
            true,
            "Europe/London",
            "09:00",
            "17:00",
            java.util.Set.of(1, 2, 3, 4, 5),
            java.util.Set.of());
    UUID policy = UUID.randomUUID();
    db.update(
        "INSERT INTO sla_policy_version(id,tenant_id,priority,version,definition)"
            + " VALUES(?,?,'P3',1,?)",
        policy,
        tenant,
        service.encode(definition));
    db.update(
        "UPDATE ticket SET status='IN_PROGRESS',sla_policy_id=?,resolution_due=? WHERE id=?",
        policy,
        java.sql.Timestamp.from(java.time.Instant.parse("2026-03-30T09:00:00Z")),
        ticket);
    service.transition(tenant, ticket, 0, "WAITING_FOR_CUSTOMER");
    assertEquals(
        7200L,
        db.queryForObject(
            "SELECT paused_remaining_seconds FROM ticket WHERE id=?", Long.class, ticket));
    var resumed =
        new SlaPolicies(
            db,
            json,
            java.time.Clock.fixed(
                java.time.Instant.parse("2026-03-30T09:00:00Z"), java.time.ZoneOffset.UTC));
    resumed.transition(tenant, ticket, 1, "IN_PROGRESS");
    assertEquals(
        java.time.Instant.parse("2026-03-30T11:00:00Z"),
        db.queryForObject(
                "SELECT resolution_due FROM ticket WHERE id=?", java.sql.Timestamp.class, ticket)
            .toInstant());
  }

  @Test
  void reopeningPreservesLateResolutionHistory() {
    seed();
    var instant = java.time.Instant.parse("2026-03-30T12:00:00Z");
    db.update(
        "UPDATE ticket SET"
            + " status='RESOLVED',resolution_due=?,resolved_at=?,resolution_summary='Fixed access'"
            + " WHERE id=?",
        java.sql.Timestamp.from(instant.minusSeconds(7200)),
        java.sql.Timestamp.from(instant.minusSeconds(3600)),
        ticket);
    new SlaPolicies(db, json, java.time.Clock.fixed(instant, java.time.ZoneOffset.UTC))
        .transition(tenant, ticket, 0, "IN_PROGRESS");
    assertEquals(
        2,
        db.queryForObject("SELECT resolution_cycle FROM ticket WHERE id=?", Integer.class, ticket));
    assertEquals(
        Boolean.TRUE,
        db.queryForObject(
            "SELECT breached FROM resolution_cycle_history WHERE ticket_id=? AND cycle=1",
            Boolean.class,
            ticket));
    assertEquals(
        "Fixed access",
        db.queryForObject(
            "SELECT summary FROM resolution_cycle_history WHERE ticket_id=?",
            String.class,
            ticket));
    assertNull(
        db.queryForObject(
            "SELECT resolved_at FROM ticket WHERE id=?", java.sql.Timestamp.class, ticket));
  }

  @Test
  void resolvingRequiresSummary() throws Exception {
    seed();
    db.update("UPDATE ticket SET status='IN_PROGRESS' WHERE id=?", ticket);
    mvc.perform(
            post(path() + "/transitions")
                .with(user(agent + "@test.local"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"status\":\"RESOLVED\",\"version\":0}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rowSecurityProtectsUnscopedQueries() {
    seed();
    db.execute(
        "DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='desk_test_runtime') THEN"
            + " CREATE ROLE desk_test_runtime NOLOGIN NOSUPERUSER NOINHERIT; END IF; END $$;");
    db.execute("GRANT USAGE ON SCHEMA public TO desk_test_runtime");
    db.execute("GRANT SELECT ON ticket TO desk_test_runtime");
    db.execute("SET LOCAL ROLE desk_test_runtime");
    db.queryForObject("SELECT set_config('desk.worker','',true)", String.class);
    db.queryForObject("SELECT set_config('desk.tenant','',true)", String.class);
    assertEquals(0, db.queryForObject("SELECT count(*) FROM ticket", Integer.class));
    db.queryForObject("SELECT set_config('desk.tenant',?,true)", String.class, tenant.toString());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM ticket", Integer.class));
    db.queryForObject("SELECT set_config('desk.tenant',?,true)", String.class, outsider.toString());
    assertEquals(0, db.queryForObject("SELECT count(*) FROM ticket", Integer.class));
    db.execute("RESET ROLE");
  }

  @Test
  void breachJobIsIdempotent() {
    seed();
    db.update(
        "UPDATE ticket SET response_due=now()-interval '1 minute',resolution_due=now()+interval '10"
            + " hours' WHERE id=?",
        ticket);
    var worker = new SlaScheduler(db);
    worker.scan();
    worker.scan();
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM audit_event WHERE ticket_id=? AND action='SLA_BREACHED'",
            Integer.class,
            ticket));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM notification WHERE tenant_id=?", Integer.class, tenant));
  }

  @Test
  void warningJobDoesNotDuplicate() {
    seed();
    db.update(
        "UPDATE ticket SET response_due=now()+interval '5 minutes',resolution_due=now()+interval"
            + " '10 hours' WHERE id=?",
        ticket);
    var worker = new SlaScheduler(db);
    worker.scan();
    worker.scan();
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM audit_event WHERE ticket_id=? AND action='SLA_WARNING'",
            Integer.class,
            ticket));
  }
}
