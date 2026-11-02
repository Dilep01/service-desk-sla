package dev.servicedesk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tenants/{tenant}")
public class TicketChanges {
  private final JdbcTemplate db;
  private final DeskController desk;
  private final SlaPolicies policies;
  private final Access access;

  public TicketChanges(JdbcTemplate db, DeskController desk, SlaPolicies policies, Access access) {
    this.db = db;
    this.desk = desk;
    this.policies = policies;
    this.access = access;
  }

  public record Priority(
      @NotNull @Pattern(regexp = "P[1-4]") String priority,
      @NotBlank @Size(max = 2000) String reason,
      long version) {}

  @PostMapping("/tickets/{id}/priority")
  @Transactional
  public void priority(
      @PathVariable UUID tenant,
      @PathVariable UUID id,
      @Valid @RequestBody Priority body,
      Principal p) {
    UUID u = desk.user(p);
    String role = desk.role(tenant, u);
    desk.staff(role);
    desk.ticket(tenant, id, u, role);
    var row =
        db.queryForList("SELECT * FROM ticket WHERE tenant_id=? AND id=? FOR UPDATE", tenant, id)
            .getFirst();
    if (((Number) row.get("version")).longValue() != body.version())
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    if (!Set.of("OPEN", "IN_PROGRESS").contains(row.get("status")))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Change priority only on open or in-progress tickets");
    var targets =
        policies.targets(tenant, body.priority(), ((Timestamp) row.get("created_at")).toInstant());
    db.update(
        "UPDATE ticket SET priority=?,sla_policy_id=?,response_due=CASE WHEN responded_at IS NULL"
            + " THEN ? ELSE response_due"
            + " END,resolution_due=?,response_warned=false,resolution_warned=false,version=version+1"
            + " WHERE tenant_id=? AND id=?",
        body.priority(),
        targets.policyId(),
        Timestamp.from(targets.response()),
        Timestamp.from(targets.resolution()),
        tenant,
        id);
    desk.audit(
        tenant,
        id,
        u,
        "PRIORITY_CHANGED",
        row.get("priority")
            + " -> "
            + body.priority()
            + "; previous deadlines="
            + row.get("response_due")
            + ","
            + row.get("resolution_due")
            + "; reason="
            + body.reason());
  }

  public record RequesterAction(
      @NotNull @Pattern(regexp = "CLOSED|IN_PROGRESS") String status, long version) {}

  @PostMapping("/tickets/{id}/requester-action")
  @Transactional
  public void requester(
      @PathVariable UUID tenant,
      @PathVariable UUID id,
      @Valid @RequestBody RequesterAction body,
      Principal p) {
    UUID u = desk.user(p);
    var t = desk.ticket(tenant, id, u, desk.role(tenant, u));
    if (!u.equals(t.get("requester_id")) || !t.get("status").equals("RESOLVED"))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    policies.transition(tenant, id, body.version(), body.status());
    desk.audit(tenant, id, u, "REQUESTER_TRANSITION", body.status());
  }

  public record Escalation(@Min(1) @Max(1440) int warningMinutes, boolean escalateToLeads) {}

  @GetMapping("/escalation")
  public Map<String, Object> settings(@PathVariable UUID tenant, Principal p) {
    access.admin(desk.role(tenant, desk.user(p)));
    return db.queryForMap(
        "SELECT warning_minutes,escalate_to_leads FROM tenant WHERE id=?", tenant);
  }

  @PutMapping("/escalation")
  @Transactional
  public void settings(
      @PathVariable UUID tenant, @Valid @RequestBody Escalation body, Principal p) {
    UUID u = desk.user(p);
    access.admin(desk.role(tenant, u));
    db.update(
        "UPDATE tenant SET warning_minutes=?,escalate_to_leads=? WHERE id=?",
        body.warningMinutes(),
        body.escalateToLeads(),
        tenant);
    desk.audit(tenant, null, u, "ESCALATION_UPDATED", body.toString());
  }

  @GetMapping("/audit")
  public List<Map<String, Object>> audit(@PathVariable UUID tenant, Principal p) {
    access.admin(desk.role(tenant, desk.user(p)));
    return db.queryForList(
        "SELECT * FROM audit_event WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200", tenant);
  }
}
