package dev.servicedesk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class Accounts {
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;
  private final DeskController desk;
  private final Access access;

  public Accounts(JdbcTemplate db, PasswordEncoder encoder, DeskController desk, Access access) {
    this.db = db;
    this.encoder = encoder;
    this.desk = desk;
    this.access = access;
  }

  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String encodePassword(String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Password must use at most 72 UTF-8 bytes");
    return encoder.encode(password);
  }

  String token() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  String issue(String kind, UUID tenant, String email, String role) {
    String token = token();
    db.update(
        "INSERT INTO account_token(hash,kind,tenant_id,email,role,expires_at)"
            + " VALUES(?,?,?,?,?,now()+interval '1 hour')",
        hash(token),
        kind,
        tenant,
        email,
        role);
    return token;
  }

  public record Organization(@NotBlank @Size(max = 100) String name) {}

  @PostMapping("/organizations")
  @Transactional
  public Map<String, UUID> organization(@Valid @RequestBody Organization body, Principal p) {
    UUID user = desk.user(p), id = UUID.randomUUID();
    db.update("INSERT INTO tenant(id,name) VALUES(?,?)", id, body.name().trim());
    db.update("INSERT INTO membership(tenant_id,user_id,role) VALUES(?,?,'ADMIN')", id, user);
    db.queryForObject("SELECT set_config('desk.tenant',?,true)", String.class, id.toString());
    desk.audit(id, null, user, "ORGANIZATION_CREATED", body.name());
    return Map.of("id", id);
  }

  public record Invitation(
      @NotBlank @Email @Size(max = 254) String email,
      @NotNull @Pattern(regexp = "REQUESTER|AGENT|LEAD|ADMIN") String role) {}

  @PostMapping("/tenants/{tenant}/invitations")
  @Transactional
  public void invite(@PathVariable UUID tenant, @Valid @RequestBody Invitation body, Principal p) {
    UUID user = desk.user(p);
    access.admin(desk.role(tenant, user));
    String email = body.email().trim().toLowerCase(Locale.ROOT),
        value = issue("INVITE", tenant, email, body.role());
    mail(
        tenant,
        email,
        "Service Desk invitation",
        "Use this one-time invitation code within one hour: " + value);
    desk.audit(tenant, null, user, "INVITED", email + ":" + body.role());
  }

  void mail(UUID tenant, String email, String subject, String body) {
    db.update(
        "INSERT INTO mail_outbox(id,tenant_id,recipient,subject,body) VALUES(?,?,?,?,?)",
        UUID.randomUUID(),
        tenant,
        email,
        subject,
        body);
  }

  public record Accept(
      @NotBlank String token,
      @NotBlank @Size(max = 100) String name,
      @NotBlank @Size(min = 12, max = 72) String password) {}

  @PostMapping("/account/accept")
  @Transactional
  public void accept(@Valid @RequestBody Accept body, Principal principal) {
    var t = consume(body.token(), "INVITE");
    String email = (String) t.get("email");
    var existing = db.queryForList("SELECT id FROM app_user WHERE email=?", email);
    UUID user;
    if (existing.isEmpty()) {
      user = UUID.randomUUID();
      db.update(
          "INSERT INTO app_user(id,email,display_name,password_hash) VALUES(?,?,?,?)",
          user,
          email,
          body.name(),
          encodePassword(body.password()));
    } else {
      if (principal == null || !principal.getName().equalsIgnoreCase(email))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Sign in to the existing account before accepting");
      user = (UUID) existing.getFirst().get("id");
    }
    db.update(
        "INSERT INTO membership(tenant_id,user_id,role) VALUES(?,?,?) ON"
            + " CONFLICT(tenant_id,user_id) DO NOTHING",
        t.get("tenant_id"),
        user,
        t.get("role"));
  }

  Map<String, Object> consume(String token, String kind) {
    return db
        .queryForList(
            "UPDATE account_token SET used_at=now() WHERE hash=? AND kind=? AND used_at IS NULL AND"
                + " expires_at>now() RETURNING *",
            hash(token),
            kind)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired code"));
  }

  public record ResetRequest(@NotBlank @Email @Size(max = 254) String email) {}

  @PostMapping("/account/reset-request")
  @Transactional
  public void resetRequest(@Valid @RequestBody ResetRequest body) {
    String email = body.email().trim().toLowerCase(Locale.ROOT);
    if (db.queryForObject("SELECT count(*) FROM app_user WHERE email=?", Integer.class, email)
        == 1) {
      String code = issue("RESET", null, email, null);
      mail(
          null,
          email,
          "Service Desk password reset",
          "Use this one-time reset code within one hour: " + code);
    }
  }

  public record Reset(
      @NotBlank String token, @NotBlank @Size(min = 12, max = 72) String password) {}

  @PostMapping("/account/reset")
  @Transactional
  public void reset(@Valid @RequestBody Reset body) {
    var t = consume(body.token(), "RESET");
    db.update(
        "UPDATE app_user SET password_hash=?,session_version=session_version+1 WHERE email=?",
        encodePassword(body.password()),
        t.get("email"));
    db.update(
        "UPDATE account_token SET used_at=now() WHERE email=? AND kind='RESET' AND used_at IS NULL",
        t.get("email"));
  }

  public record MemberUpdate(
      @NotNull @Pattern(regexp = "REQUESTER|AGENT|LEAD|ADMIN") String role, boolean active) {}

  @PutMapping("/tenants/{tenant}/members/{member}")
  @Transactional
  public void member(
      @PathVariable UUID tenant,
      @PathVariable UUID member,
      @Valid @RequestBody MemberUpdate body,
      Principal p) {
    UUID user = desk.user(p);
    access.admin(desk.role(tenant, user));
    db.queryForObject("SELECT id FROM tenant WHERE id=? FOR UPDATE", UUID.class, tenant);
    var rows =
        db.queryForList(
            "SELECT role,active FROM membership WHERE tenant_id=? AND user_id=?", tenant, member);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var old = rows.getFirst();
    if (old.get("role").equals("ADMIN")
        && (Boolean) old.get("active")
        && (!body.active() || !body.role().equals("ADMIN"))
        && db.queryForObject(
                "SELECT count(*) FROM membership WHERE tenant_id=? AND role='ADMIN' AND active",
                Integer.class,
                tenant)
            <= 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Keep at least one active administrator");
    db.update(
        "UPDATE membership SET role=?,active=? WHERE tenant_id=? AND user_id=?",
        body.role(),
        body.active(),
        tenant,
        member);
    if (!body.active() || body.role().equals("REQUESTER")) {
      db.update(
          "UPDATE ticket SET assignee_id=NULL,version=version+1 WHERE tenant_id=? AND"
              + " assignee_id=?",
          tenant,
          member);
      db.update("DELETE FROM team_member WHERE tenant_id=? AND user_id=?", tenant, member);
    }
    desk.audit(
        tenant, null, user, "MEMBER_UPDATED", member + ":" + body.role() + ":" + body.active());
  }

  @PostMapping("/account/revoke-sessions")
  public void revoke(Principal p) {
    db.update("UPDATE app_user SET session_version=session_version+1 WHERE email=?", p.getName());
  }
}
