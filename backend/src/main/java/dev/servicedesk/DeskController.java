package dev.servicedesk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DeskController {
  private final JdbcTemplate db;
  private final Access access;
  private final SlaPolicies policies;
  private final TicketIdempotency idempotency;

  public DeskController(
      JdbcTemplate db, Access access, SlaPolicies policies, TicketIdempotency idempotency) {
    this.db = db;
    this.access = access;
    this.policies = policies;
    this.idempotency = idempotency;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "up");
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  UUID user(Principal p) {
    return db.queryForObject("SELECT id FROM app_user WHERE email=?", UUID.class, p.getName());
  }

  String role(UUID tenant, UUID user) {
    return db
        .query(
            "SELECT role FROM membership WHERE tenant_id=? AND user_id=? AND active",
            (r, n) -> r.getString(1),
            tenant,
            user)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  void staff(String role) {
    if (role.equals("REQUESTER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  Map<String, Object> ticket(UUID tenant, UUID id, UUID user, String role) {
    return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(db)
            .queryForList(
                "SELECT * FROM ticket WHERE tenant_id=:tenant AND id=:id AND ("
                    + Access.visibility(role)
                    + ")",
                Map.of("tenant", tenant, "id", id, "user", user))
            .stream()
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  void audit(UUID tenant, UUID ticket, UUID actor, String action, String details) {
    db.update(
        "INSERT INTO audit_event(id,tenant_id,ticket_id,actor_id,action,details)"
            + " VALUES(?,?,?,?,?,?)",
        UUID.randomUUID(),
        tenant,
        ticket,
        actor,
        action,
        details);
  }

  @GetMapping("/me")
  public Map<String, Object> me(Principal p) {
    UUID id = user(p);
    return Map.of(
        "email",
        p.getName(),
        "id",
        id,
        "memberships",
        db.queryForList(
            "SELECT t.id,t.name,m.role FROM membership m JOIN tenant t ON t.id=m.tenant_id WHERE"
                + " m.user_id=? AND m.active ORDER BY t.name",
            id));
  }

  @GetMapping("/tenants/{tenant}/tickets")
  public List<Map<String, Object>> tickets(
      @PathVariable UUID tenant,
      Principal p,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String status) {
    UUID u = user(p);
    String r = role(tenant, u);
    return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(db)
        .queryForList(
            "SELECT * FROM ticket WHERE tenant_id=:tenant AND ("
                + Access.visibility(r)
                + ") AND (:search='' OR title ILIKE '%' || :search || '%') AND (:status='' OR"
                + " status=:status) ORDER BY created_at DESC,id LIMIT 50 OFFSET :offset",
            Map.of(
                "tenant",
                tenant,
                "user",
                u,
                "offset",
                Math.max(0, Math.min(page, 10000)) * 50,
                "search",
                search.substring(0, Math.min(search.length(), 200)),
                "status",
                status));
  }

  public record NewTicket(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 20000) String description,
      @Pattern(regexp = "P[1-4]") @NotNull String priority,
      UUID teamId,
      @Pattern(regexp = "INCIDENT|REQUEST") String ticketType) {}

  @PostMapping("/tenants/{tenant}/tickets")
  @Transactional
  public Map<String, Object> create(
      @PathVariable UUID tenant,
      @Valid @RequestBody NewTicket body,
      Principal p,
      @RequestHeader(name = "Idempotency-Key", required = false) UUID requestKey) {
    UUID u = user(p);
    role(tenant, u);
    UUID existing = idempotency.existing(tenant, u, requestKey, body);
    if (existing != null) return Map.of("id", existing);
    UUID id = UUID.randomUUID();
    Instant now = policies.now();
    if (body.teamId() != null
        && db.queryForObject(
                "SELECT count(*) FROM support_team WHERE tenant_id=? AND id=?",
                Integer.class,
                tenant,
                body.teamId())
            != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid team");
    var targets = policies.targets(tenant, body.priority(), now);
    db.update(
        "INSERT INTO"
            + " ticket(id,tenant_id,title,description,requester_id,priority,response_due,resolution_due)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        tenant,
        body.title(),
        body.description(),
        u,
        body.priority(),
        Timestamp.from(targets.response()),
        Timestamp.from(targets.resolution()));
    db.update("UPDATE ticket SET team_id=? WHERE tenant_id=? AND id=?", body.teamId(), tenant, id);
    db.update(
        "UPDATE ticket SET ticket_type=? WHERE tenant_id=? AND id=?",
        body.ticketType() == null ? "INCIDENT" : body.ticketType(),
        tenant,
        id);
    db.update(
        "UPDATE ticket SET sla_policy_id=? WHERE tenant_id=? AND id=?",
        targets.policyId(),
        tenant,
        id);
    audit(tenant, id, u, "CREATED", body.title());
    idempotency.record(tenant, u, requestKey, body, id);
    return Map.of("id", id);
  }

  @GetMapping("/tenants/{tenant}/tickets/{id}")
  public Map<String, Object> detail(@PathVariable UUID tenant, @PathVariable UUID id, Principal p) {
    UUID u = user(p);
    String r = role(tenant, u);
    return Map.of(
        "ticket",
        ticket(tenant, id, u, r),
        "comments",
        db.queryForList(
            "SELECT c.*,u.display_name FROM comment c JOIN app_user u ON u.id=c.author_id WHERE"
                + " c.tenant_id=? AND c.ticket_id=?"
                + (r.equals("REQUESTER") ? " AND NOT internal" : "")
                + " ORDER BY c.created_at",
            tenant,
            id));
  }

  public record Reply(@NotBlank @Size(max = 20000) String body, boolean internal) {}

  @PostMapping("/tenants/{tenant}/tickets/{id}/comments")
  @Transactional
  public void comment(
      @PathVariable UUID tenant,
      @PathVariable UUID id,
      @Valid @RequestBody Reply body,
      Principal p) {
    UUID u = user(p);
    String r = role(tenant, u);
    Map<String, Object> current = ticket(tenant, id, u, r);
    if (body.internal()) staff(r);
    db.update(
        "INSERT INTO comment(id,tenant_id,ticket_id,author_id,body,internal) VALUES(?,?,?,?,?,?)",
        UUID.randomUUID(),
        tenant,
        id,
        u,
        body.body(),
        body.internal());
    if (!r.equals("REQUESTER") && !body.internal() && !u.equals(current.get("requester_id")))
      db.update(
          "UPDATE ticket SET responded_at=COALESCE(responded_at,now()) WHERE tenant_id=? AND id=?",
          tenant,
          id);
    if (!body.internal()) {
      if (u.equals(current.get("requester_id"))
          && current.get("status").equals("WAITING_FOR_CUSTOMER")) {
        policies.transition(
            tenant, id, ((Number) current.get("version")).longValue(), "IN_PROGRESS");
        audit(tenant, id, u, "STATUS_CHANGED", "Customer replied; resumed resolution clock");
      }
      Object recipient =
          u.equals(current.get("requester_id"))
              ? current.get("assignee_id")
              : current.get("requester_id");
      if (recipient != null && !recipient.equals(u))
        db.update(
            "INSERT INTO notification(id,tenant_id,user_id,message) VALUES(?,?,?,?)",
            UUID.randomUUID(),
            tenant,
            recipient,
            "New public reply on ticket " + id);
    }
    audit(tenant, id, u, "COMMENT_ADDED", body.internal() ? "Internal" : "Public");
  }

  public record Transition(
      @NotBlank String status, long version, @Size(max = 10000) String summary) {}

  @PostMapping("/tenants/{tenant}/tickets/{id}/transitions")
  @Transactional
  public void transition(
      @PathVariable UUID tenant,
      @PathVariable UUID id,
      @Valid @RequestBody Transition body,
      Principal p) {
    UUID u = user(p);
    String r = role(tenant, u);
    staff(r);
    Map<String, Object> t = ticket(tenant, id, u, r);
    String from = (String) t.get("status");
    if (!Sla.transition(from, body.status()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transition");
    if (body.status().equals("RESOLVED") && (body.summary() == null || body.summary().isBlank()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A resolution summary is required");
    policies.transition(tenant, id, body.version(), body.status());
    if (body.status().equals("RESOLVED"))
      db.update(
          "UPDATE ticket SET resolution_summary=? WHERE tenant_id=? AND id=?",
          body.summary(),
          tenant,
          id);
    audit(tenant, id, u, "STATUS_CHANGED", from + " → " + body.status());
    if (!u.equals(t.get("requester_id")))
      db.update(
          "INSERT INTO notification(id,tenant_id,user_id,message) VALUES(?,?,?,?)",
          UUID.randomUUID(),
          tenant,
          t.get("requester_id"),
          "Ticket " + id + " changed to " + body.status());
  }

  @GetMapping("/tenants/{tenant}/tickets/{id}/resolution-cycles")
  public List<Map<String, Object>> cycles(
      @PathVariable UUID tenant, @PathVariable UUID id, Principal p) {
    UUID u = user(p);
    ticket(tenant, id, u, role(tenant, u));
    return db.queryForList(
        "SELECT cycle,deadline,resolved_at,breached,summary FROM resolution_cycle_history WHERE"
            + " tenant_id=? AND ticket_id=? ORDER BY cycle",
        tenant,
        id);
  }

  @GetMapping("/tenants/{tenant}/members")
  public List<Map<String, Object>> members(@PathVariable UUID tenant, Principal p) {
    staff(role(tenant, user(p)));
    return db.queryForList(
        "SELECT u.id,u.display_name,m.role,m.active FROM membership m JOIN app_user u ON"
            + " u.id=m.user_id WHERE m.tenant_id=?",
        tenant);
  }

  public record Assignment(@NotNull UUID assignee, long version) {}

  @PostMapping("/tenants/{tenant}/tickets/{id}/assignment")
  @Transactional
  public void assign(
      @PathVariable UUID tenant,
      @PathVariable UUID id,
      @Valid @RequestBody Assignment body,
      Principal p) {
    UUID u = user(p);
    String r = role(tenant, u);
    staff(r);
    Map<String, Object> target = ticket(tenant, id, u, r);
    staff(role(tenant, body.assignee()));
    access.eligible(tenant, (UUID) target.get("team_id"), body.assignee());
    int changed =
        db.update(
            "UPDATE ticket SET assignee_id=?,version=version+1,updated_at=now() WHERE tenant_id=?"
                + " AND id=? AND version=?",
            body.assignee(),
            tenant,
            id,
            body.version());
    if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
    audit(tenant, id, u, "ASSIGNED", body.assignee().toString());
    db.update(
        "INSERT INTO notification(id,tenant_id,user_id,message) VALUES(?,?,?,?)",
        UUID.randomUUID(),
        tenant,
        body.assignee(),
        "Ticket assigned: " + id);
  }

  @GetMapping("/tenants/{tenant}/notifications")
  public List<Map<String, Object>> notifications(@PathVariable UUID tenant, Principal p) {
    UUID u = user(p);
    role(tenant, u);
    return db.queryForList(
        "SELECT * FROM notification WHERE tenant_id=? AND user_id=? ORDER BY created_at DESC LIMIT"
            + " 100",
        tenant,
        u);
  }

  @GetMapping("/tenants/{tenant}/reports")
  public List<Map<String, Object>> reports(@PathVariable UUID tenant, Principal p) {
    access.admin(role(tenant, user(p)));
    return db.queryForList(
        "SELECT status,count(*) AS total,count(*) FILTER(WHERE response_breached OR"
            + " resolution_breached) AS breached FROM ticket WHERE tenant_id=? GROUP BY status",
        tenant);
  }
}
