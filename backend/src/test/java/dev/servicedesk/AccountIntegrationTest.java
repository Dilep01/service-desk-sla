package dev.servicedesk;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
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
class AccountIntegrationTest {
  @Test
  void repeatedLoginsAreThrottled() throws Exception {
    for (int i = 0; i < 10; i++)
      mvc.perform(
              post("/api/login")
                  .with(csrf())
                  .param("username", "unknown-login@test.local")
                  .param("password", "incorrect"))
          .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/login")
                .with(csrf())
                .param("username", "unknown-login@test.local")
                .param("password", "incorrect"))
        .andExpect(status().isTooManyRequests());
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired Accounts accounts;
  UUID tenant = UUID.randomUUID(), userId = UUID.randomUUID();
  String email = userId + "@test.local";

  void seed() {
    db.update("INSERT INTO tenant(id,name) VALUES(?,'Accounts test')", tenant);
    db.update(
        "INSERT INTO app_user(id,email,display_name,password_hash)"
            + " VALUES(?,?,'Admin','old-password-hash')",
        userId,
        email);
    db.update("INSERT INTO membership(tenant_id,user_id,role) VALUES(?,?,'ADMIN')", tenant, userId);
  }

  @Test
  void lastAdminProtected() throws Exception {
    seed();
    mvc.perform(
            put("/api/tenants/" + tenant + "/members/" + userId)
                .with(user(email))
                .with(csrf())
                .contentType("application/json")
                .content("{\"role\":\"REQUESTER\",\"active\":false}"))
        .andExpect(status().isConflict());
  }

  @Test
  void resetIsSingleUseAndRevokesSessions() throws Exception {
    seed();
    String code = accounts.issue("RESET", null, email, null);
    String body = "{\"token\":\"" + code + "\",\"password\":\"changed-password-2026\"}";
    mvc.perform(
            post("/api/account/reset").with(csrf()).contentType("application/json").content(body))
        .andExpect(status().isOk());
    assertEquals(
        1L,
        db.queryForObject("SELECT session_version FROM app_user WHERE id=?", Long.class, userId));
    mvc.perform(
            post("/api/account/reset").with(csrf()).contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invitationCannotOverwriteExistingAccount() throws Exception {
    seed();
    String code = accounts.issue("INVITE", tenant, email, "AGENT");
    mvc.perform(
            post("/api/account/accept")
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"token\":\""
                        + code
                        + "\",\"name\":\"Intruder\",\"password\":\"changed-password-2026\"}"))
        .andExpect(status().isConflict());
    assertEquals(
        "old-password-hash",
        db.queryForObject("SELECT password_hash FROM app_user WHERE id=?", String.class, userId));
  }

  @Test
  void expiredInvitationRejected() throws Exception {
    seed();
    String code = accounts.issue("INVITE", tenant, "new@test.local", "REQUESTER");
    db.update(
        "UPDATE account_token SET expires_at=now()-interval '1 second' WHERE hash=?",
        Accounts.hash(code));
    mvc.perform(
            post("/api/account/accept")
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"token\":\""
                        + code
                        + "\",\"name\":\"New\",\"password\":\"new-password-2026\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void notificationEnqueuesMailInSameTransaction() {
    seed();
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO notification(id,tenant_id,user_id,message) VALUES(?,?,?,'Assigned')",
        id,
        tenant,
        userId);
    assertEquals(
        email, db.queryForObject("SELECT recipient FROM mail_outbox WHERE id=?", String.class, id));
  }

  @Test
  void resetRequestHasGenericResponse() throws Exception {
    mvc.perform(
            post("/api/account/reset-request")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"unknown@test.local\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void staleSessionRejected() throws Exception {
    seed();
    var session = new org.springframework.mock.web.MockHttpSession();
    session.setAttribute("accountVersion", 0L);
    db.update("UPDATE app_user SET session_version=1 WHERE id=?", userId);
    mvc.perform(get("/api/me").session(session).with(user(email)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void mailFailureRetriesWithoutLosingWork() {
    seed();
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO mail_outbox(id,tenant_id,recipient,subject,body) VALUES(?,?,?,'Retry"
            + " test','Content')",
        id,
        tenant,
        email);
    var sender = org.mockito.Mockito.mock(org.springframework.mail.javamail.JavaMailSender.class);
    org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("offline"))
        .when(sender)
        .send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
    var worker = new MailDelivery(db, sender, true);
    worker.deliver();
    assertEquals(
        1, db.queryForObject("SELECT attempts FROM mail_outbox WHERE id=?", Integer.class, id));
    assertNull(
        db.queryForObject(
            "SELECT delivered_at FROM mail_outbox WHERE id=?", java.sql.Timestamp.class, id));
    db.update("UPDATE mail_outbox SET available_at=now() WHERE id=?", id);
    org.mockito.Mockito.doNothing()
        .when(sender)
        .send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
    worker.deliver();
    assertNotNull(
        db.queryForObject(
            "SELECT delivered_at FROM mail_outbox WHERE id=?", java.sql.Timestamp.class, id));
  }
}
